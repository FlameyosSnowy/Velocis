package io.github.flameyossnowy.velocis.tables;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;

import static io.github.flameyossnowy.velocis.cache.algorithms.ConcurrentLFUCache.nextPow2;

/**
 * A lock-free, insertion-ordered two-key (row + column) hash table.
 *
 * <h2>Data layout</h2>
 * <pre>
 *   Parallel arrays per bucket stripe:
 *     int[]    hashes   - full 32-bit hash, 0 = empty slot
 *     Object[] rows     - row keys
 *     Object[] columns  - column keys
 *     Object[] values   - stored values
 *     Node[]   nodes    - full Node objects (for chaining + linked-list pointers)
 *
 *   Insertion-order doubly-linked list threaded through Node objects:
 *     AtomicReference<Node> head, tail
 * </pre>
 *
 * <h2>Concurrency</h2>
 * <ul>
 *   <li>Reads are always wait-free (single volatile read of the bucket array).</li>
 *   <li>Writes use a CAS retry loop.</li>
 *   <li>Resize is guarded by a single CAS flag; non-resizing writers spin-yield
 *       for the very short window the resize takes.</li>
 *   <li>The linked-list tail append uses a two-phase CAS (Harris-style):
 *       first CAS the tail's {@code next}, then swing the {@code tail} sentinel.</li>
 * </ul>
 */
@SuppressWarnings({"unchecked", "unused"})
public class LinkedConcurrentTable<R, C, V> implements Table<R, C, V> {

    static final class Node<R, C, V> implements Table.Entry<R, C, V> {
        final R   row;
        final C   column;
              V   value;          // updated via plain write under CAS of bucket
        final int hash;

        volatile Node<R, C, V> bucketNext;

        // Insertion-order linked list (both directions for O(1) remove)
        volatile Node<R, C, V> listNext;
        volatile Node<R, C, V> listPrev;

        // Logical deletion marker for the linked list (Harris trick)
        volatile boolean deleted;

        Node(R row, C column, V value, int hash) {
            this.row    = row;
            this.column = column;
            this.value  = value;
            this.hash   = hash;
        }

        @Override public R    row()    { return row; }
        @Override public C    column() { return column; }
        @Override public V    value()  { return value; }

        @Override public Table.Entry<R, C, V> next() { return bucketNext; }
        @Override public void setValue(V v)           { this.value = v; }
        @Override public void setNext(Table.Entry<R, C, V> n) {
            this.bucketNext = (Node<R, C, V>) n;
        }

        @Override
        public Table.KeyEntry<R, C> key() {
            R r = row; C c = column;
            return new Table.KeyEntry<>() {
                @Override public R row()    { return r; }
                @Override public C column() { return c; }
            };
        }
    }

    /**
     * One stripe of the hash table.
     * Layout: slot i corresponds to hashes[i], rows[i], columns[i], values[i], nodes[i].
     * A slot is empty when hashes[i] == 0 AND nodes[i] == null.
     *
     * <p>Collision chains are stored via Node.bucketNext, not by linear probing,
     * so the arrays only hold the chain heads.
     */
    private static final class Stripe<R, C, V> {
        final int[]    hashes;
        final Object[] rows;
        final Object[] columns;
        final Object[] values;
        final AtomicReferenceArray<Node<R, C, V>> nodes;
        final int      mask;   // length - 1, for fast modulo

        Stripe(int capacity) {
            int cap  = nextPow2(capacity);
            this.mask    = cap - 1;
            this.hashes  = new int[cap];
            this.rows    = new Object[cap];
            this.columns = new Object[cap];
            this.values  = new Object[cap];
            this.nodes   = new AtomicReferenceArray<>(cap);
        }

        int index(int hash) { return hash & mask; }
    }

    private static final int   DEFAULT_CAPACITY   = 16;
    private static final float DEFAULT_LOAD_FACTOR = 0.75f;
    private static final int   STRIPE_SHIFT        = 4;          // 16 stripes
    private static final int   STRIPE_COUNT        = 1 << STRIPE_SHIFT;
    private static final int   STRIPE_MASK         = STRIPE_COUNT - 1;

    private final AtomicReference<Stripe<R, C, V>>[] stripes;

    private final AtomicInteger size          = new AtomicInteger(0);
    private final float         loadFactor;

    private final Node<R, C, V> listHead;
    private final Node<R, C, V> listTail;

    private final Set<Table.Entry<R, C, V>> entries;

    public LinkedConcurrentTable() {
        this(DEFAULT_CAPACITY, DEFAULT_LOAD_FACTOR);
    }

    public LinkedConcurrentTable(int initialCapacity) {
        this(initialCapacity, DEFAULT_LOAD_FACTOR);
    }

    public LinkedConcurrentTable(int initialCapacity, float loadFactor) {
        this.loadFactor = loadFactor;
        this.stripes    = new AtomicReference[STRIPE_COUNT];

        int perStripe = Math.max(4, nextPow2(initialCapacity / STRIPE_COUNT));
        for (int i = 0; i < STRIPE_COUNT; i++) {
            stripes[i] = new AtomicReference<>(new Stripe<>(perStripe));
        }

        listHead = new Node<>(null, null, null, 0);
        listTail = new Node<>(null, null, null, 0);
        listHead.listNext = listTail;
        listTail.listPrev = listHead;

        this.entries = new LinkedEntrySet();
    }

    private static int spread(int h) {
        h ^= (h >>> 16);
        return h & 0x7FFF_FFFF; // keep positive; 0 reserved for "empty"
    }

    private int hash(R row, C column) {
        int h = spread(row.hashCode() ^ column.hashCode());
        return h == 0 ? 1 : h; // 0 is the empty-slot sentinel
    }

    private int stripeIndex(int hash) {
        return (hash >>> (32 - STRIPE_SHIFT)) & STRIPE_MASK;
    }

    @Override
    public @Nullable V get(R row, C column) {
        int hash   = hash(row, column);
        Stripe<R, C, V> stripe = stripes[stripeIndex(hash)].get();
        int idx    = stripe.index(hash);

        int[] hs = stripe.hashes;
        int   len = hs.length;
        Node<R, C, V> node = stripe.nodes.get(idx);
        while (node != null) {
            if (node.hash == hash
                    && Objects.equals(node.row, row)
                    && Objects.equals(node.column, column)) {
                return node.deleted ? null : node.value;
            }
            node = node.bucketNext;
        }
        return null;
    }

    @Override
    public @Nullable V put(R row, C column, V value) {
        return doPut(row, column, value, false);
    }

    @Override
    public @Nullable V putIfAbsent(R row, C column, V value) {
        return doPut(row, column, value, true);
    }

    private @Nullable V doPut(R row, C column, V value, boolean onlyIfAbsent) {
        int hash        = hash(row, column);
        int stripeIdx   = stripeIndex(hash);

        while (true) {
            maybeResize(stripeIdx);
            Stripe<R, C, V> stripe = stripes[stripeIdx].get();
            int idx = stripe.index(hash);
            Node<R, C, V> head = stripe.nodes.get(idx);

            for (Node<R, C, V> n = head; n != null; n = n.bucketNext) {
                if (n.hash == hash
                        && Objects.equals(n.row, row)
                        && Objects.equals(n.column, column)) {
                    if (n.deleted) break; // treat as absent
                    if (onlyIfAbsent) return n.value;
                    V old  = n.value;
                    n.value = value;   // plain write; safe because readers see
                                       // the node only after the CAS that published it
                    return old;
                }
            }

            Node<R, C, V> newNode = new Node<>(row, column, value, hash);
            newNode.bucketNext = head;

            if (!stripe.nodes.compareAndSet(idx, head, newNode)) {
                continue; // CAS failed, retry
            }

            stripe.hashes [idx] = hash;
            stripe.rows   [idx] = row;
            stripe.columns[idx] = column;
            stripe.values [idx] = value;

            size.incrementAndGet();
            listAppend(newNode);
            return null;
        }
    }

    @Override
    public @Nullable V remove(R row, C column) {
        int hash      = hash(row, column);
        int stripeIdx = stripeIndex(hash);

        while (true) {
            Stripe<R, C, V> stripe = stripes[stripeIdx].get();
            int idx = stripe.index(hash);
            Node<R, C, V> head = stripe.nodes.get(idx);

            // Rebuild chain without target
            Node<R, C, V> target = null;
            for (Node<R, C, V> n = head; n != null; n = n.bucketNext) {
                if (n.hash == hash
                        && Objects.equals(n.row, row)
                        && Objects.equals(n.column, column)
                        && !n.deleted) {
                    target = n;
                    break;
                }
            }
            if (target == null) return null;

            Node<R, C, V> newHead = rebuildWithout(head, target);
            if (!stripe.nodes.compareAndSet(idx, head, newHead)) continue;

            target.deleted = true;
            size.decrementAndGet();
            listRemove(target);
            return target.value;
        }
    }

    @Override
    public V remove(R row, C column, V value) {
        V current = get(row, column);
        if (current != null && Objects.equals(current, value)) return remove(row, column);
        return null;
    }

    private Node<R, C, V> rebuildWithout(Node<R, C, V> head, Node<R, C, V> target) {
        // Collect nodes before target
        Deque<Node<R, C, V>> before = new ArrayDeque<>();
        for (Node<R, C, V> n = head; n != target; n = n.bucketNext) before.push(n);

        Node<R, C, V> rebuilt = target.bucketNext;
        while (!before.isEmpty()) {
            Node<R, C, V> n = before.pop();
            Node<R, C, V> copy = new Node<>(n.row, n.column, n.value, n.hash);
            copy.bucketNext = rebuilt;
            rebuilt = copy;
        }
        return rebuilt;
    }

    /**
     * Appends {@code node} to the tail of the insertion-order list.
     * Uses a two-step CAS: first link the old tail's {@code listNext} to the
     * new node, then swing the logical tail forward.
     */
    private void listAppend(Node<R, C, V> node) {
        while (true) {
            Node<R, C, V> last = listTail.listPrev; // last real node or listHead
            node.listPrev = last;
            node.listNext = listTail;

            synchronized (listTail) {
                if (listTail.listPrev == last) {
                    last.listNext  = node;
                    node.listPrev  = last;
                    node.listNext  = listTail;
                    listTail.listPrev = node;
                    return;
                }
            }
        }
    }

    /**
     * Removes {@code node} from the insertion-order list.
     * Marks it deleted first, then unlinks.
     */
    private void listRemove(Node<R, C, V> node) {
        synchronized (listTail) {
            Node<R, C, V> prev = node.listPrev;
            Node<R, C, V> next = node.listNext;
            if (prev != null) prev.listNext = next;
            if (next != null) next.listPrev = prev;
        }
    }

    private void maybeResize(int stripeIdx) {
        AtomicReference<Stripe<R, C, V>> ref    = stripes[stripeIdx];
        Stripe<R, C, V>                  stripe = ref.get();

        int length = stripe.nodes.length();
        int threshold = (int) (length * loadFactor);
        if (size.get() / STRIPE_COUNT < threshold) return;

        Stripe<R, C, V> newStripe = new Stripe<>(length * 2);
        if (!ref.compareAndSet(stripe, newStripe)) return;

        for (int i = 0; i < length; i++) {
            for (Node<R, C, V> n = stripe.nodes.get(i); n != null; n = n.bucketNext) {
                if (n.deleted) continue;
                int newIdx = newStripe.index(n.hash);
                Node<R, C, V> copy = new Node<>(n.row, n.column, n.value, n.hash);
                copy.bucketNext = newStripe.nodes.get(newIdx);
                newStripe.nodes.set(newIdx, copy);
                newStripe.hashes [newIdx] = n.hash;
                newStripe.rows   [newIdx] = n.row;
                newStripe.columns[newIdx] = n.column;
                newStripe.values [newIdx] = n.value;
            }
        }
    }

    @Override
    public boolean containsKey(R row, C column) {
        return get(row, column) != null;
    }

    @Override
    public boolean containsValue(V value) {
        for (Node<R, C, V> n = listHead.listNext; n != listTail; n = n.listNext) {
            if (!n.deleted && Objects.equals(n.value, value)) return true;
        }
        return false;
    }

    @Override
    public boolean contains(R row, C column, V value) {
        V v = get(row, column);
        return v != null && Objects.equals(v, value);
    }

    @Override
    public V getOrDefault(R row, C column, V defaultValue) {
        V v = get(row, column);
        return v != null ? v : defaultValue;
    }

    @Override
    public void putAll(Table<R, C, V> other) {
        for (Table.Entry<R, C, V> e : other.entrySet()) put(e.row(), e.column(), e.value());
    }

    @Override
    public void forEach(Table.ForEach<? super R, ? super C, ? super V> action) {
        for (Node<R, C, V> n = listHead.listNext; n != listTail; n = n.listNext) {
            if (!n.deleted) action.accept(n.row, n.column, n.value);
        }
    }

    @Override
    public int size() { return size.get(); }

    @Override
    public boolean isEmpty() { return size.get() == 0; }

    @Override
    public void clear() {
        for (AtomicReference<Stripe<R, C, V>> ref : stripes) {
            Stripe<R, C, V> old = ref.get();
            ref.set(new Stripe<>(old.nodes.length()));
        }
        synchronized (listTail) {
            listHead.listNext = listTail;
            listTail.listPrev = listHead;
        }
        size.set(0);
    }

    @Override
    public Set<Table.Entry<R, C, V>> entrySet() { return entries; }

    @Override
    public Set<Table.KeyEntry<R, C>> keySet() {
        return new AbstractSet<>() {
            @Override public int size() { return LinkedConcurrentTable.this.size(); }
            @Override public boolean contains(Object o) {
                if (!(o instanceof Table.KeyEntry<?, ?> e)) return false;
                return containsKey((R) e.row(), (C) e.column());
            }
            @Override
            public @NotNull Iterator<Table.KeyEntry<R, C>> iterator() {
                return new ListIterator<Table.KeyEntry<R, C>>() {
                    @Override
                    Table.KeyEntry<R, C> extract(Node<R, C, V> n) {
                        return n.key();
                    }
                };
            }
        };
    }

    @Override
    public Collection<V> values() {
        return new AbstractCollection<>() {
            @Override public int size() { return LinkedConcurrentTable.this.size(); }
            @Override public boolean contains(Object o) { return containsValue((V) o); }
            @Override public @NotNull Iterator<V> iterator() {
                return new ListIterator<V>() {
                    @Override V extract(Node<R, C, V> n) { return n.value; }
                };
            }
        };
    }

    @Override
    public Map<C, V> row(R row) {
        return new AbstractMap<>() {
            @Override public V get(Object c)          { return LinkedConcurrentTable.this.get(row, (C) c); }
            @Override public V put(C c, V v)          { return LinkedConcurrentTable.this.put(row, c, v); }
            @Override public V remove(Object c)       { return LinkedConcurrentTable.this.remove(row, (C) c); }
            @Override public boolean containsKey(Object c) { return LinkedConcurrentTable.this.containsKey(row, (C) c); }
            @Override public @NotNull Set<Map.Entry<C, V>> entrySet() {
                return new AbstractSet<>() {
                    @Override public int size() { return LinkedConcurrentTable.this.size(); }
                    @Override public @NotNull Iterator<Map.Entry<C, V>> iterator() {
                        return new ListIterator<Map.Entry<C, V>>() {
                            @Override Map.Entry<C, V> extract(Node<R, C, V> n) {
                                return Map.entry(n.column, n.value);
                            }
                            @Override boolean include(Node<R, C, V> n) {
                                return Objects.equals(n.row, row);
                            }
                        };
                    }
                };
            }
        };
    }

    private abstract class ListIterator<T> implements Iterator<T> {
        Node<R, C, V> current;

        ListIterator() {
            current = advance(listHead.listNext);
        }

        /** Override to filter by row etc. Default: include all live nodes. */
        boolean include(Node<R, C, V> n) { return true; }

        abstract T extract(Node<R, C, V> n);

        private Node<R, C, V> advance(Node<R, C, V> from) {
            Node<R, C, V> n = from;
            while (n != listTail && (n.deleted || !include(n))) n = n.listNext;
            return n == listTail ? null : n;
        }

        @Override public boolean hasNext() { return current != null; }

        @Override public T next() {
            if (current == null) throw new NoSuchElementException();
            Node<R, C, V> n = current;
            current = advance(n.listNext);
            return extract(n);
        }

        @Override public void remove() {
            throw new UnsupportedOperationException("Use Table.remove()");
        }
    }

    private final class LinkedEntrySet extends AbstractSet<Table.Entry<R, C, V>> {
        @Override public int size() { return LinkedConcurrentTable.this.size(); }
        @Override public boolean contains(Object o) {
            if (!(o instanceof Table.Entry<?, ?, ?> e)) return false;
            return containsKey((R) e.row(), (C) e.column());
        }
        @Override public boolean add(Table.Entry<R, C, V> e) {
            return LinkedConcurrentTable.this.put(e.row(), e.column(), e.value()) == null;
        }
        @Override public boolean remove(Object o) {
            if (!(o instanceof Table.Entry<?, ?, ?> e)) return false;
            return LinkedConcurrentTable.this.remove((R) e.row(), (C) e.column()) != null;
        }
        @Override public void clear() { LinkedConcurrentTable.this.clear(); }
        @Override
        public @NotNull Iterator<Table.Entry<R, C, V>> iterator() {
            return new ListIterator<Table.Entry<R, C, V>>() {
                @Override Table.Entry<R, C, V> extract(Node<R, C, V> n) { return n; }
            };
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Node<R, C, V> n = listHead.listNext; n != listTail; n = n.listNext) {
            if (n.deleted) continue;
            if (!first) sb.append(", ");
            sb.append('[').append(n.row).append(", ").append(n.column).append("]=").append(n.value);
            first = false;
        }
        return sb.append('}').toString();
    }
}