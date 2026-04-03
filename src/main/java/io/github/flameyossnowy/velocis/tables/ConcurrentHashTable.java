package io.github.flameyossnowy.velocis.tables;

import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.LongAdder;

@SuppressWarnings({"unchecked", "unused"})
public class ConcurrentHashTable<R, C, V> implements Table<R, C, V> {

    private static final int   INITIAL_CAPACITY = 16;
    private static final float LOAD_FACTOR      = 0.75f;

    /**
     * Holds the current bucket array. Wrapped in AtomicReference so resize
     * can CAS-swap it atomically without locking readers.
     */
    private final AtomicReference<AtomicReferenceArray<Node<R, C, V>>> tableRef;

    private final LongAdder    size         = new LongAdder();
    private final AtomicBoolean resizing    = new AtomicBoolean(false);
    private final float         loadFactor;

    private volatile EntrySet entrySetView;
    private volatile KeySet   keySetView;
    private volatile Values   valuesView;

    private static final class Node<R, C, V> implements Table.Entry<R, C, V> {
        final R   row;
        final C   column;
        final V   value;
        final int hash;
        // `next` is effectively final after construction, we never reassign it.
        // Building a new chain means allocating new Node objects.
        final Node<R, C, V> next;

        Node(R row, C column, V value, int hash, Node<R, C, V> next) {
            this.row    = row;
            this.column = column;
            this.value  = value;
            this.hash   = hash;
            this.next   = next;
        }

        @Override public R              row()    { return row; }
        @Override public C              column() { return column; }
        @Override public V              value()  { return value; }
        @Override public Table.Entry<R, C, V> next()   { return next; }

        @Override public void setValue(V v)                        { throw new UnsupportedOperationException(); }
        @Override public void setNext(Table.Entry<R, C, V> next)   { throw new UnsupportedOperationException(); }

        @Override
        public Table.KeyEntry<R, C> key() {
            R r = row; C c = column;
            return new Table.KeyEntry<>() {
                @Override public R row()    { return r; }
                @Override public C column() { return c; }
            };
        }
    }

    public ConcurrentHashTable() {
        this(INITIAL_CAPACITY, LOAD_FACTOR);
    }

    public ConcurrentHashTable(int initialCapacity) {
        this(initialCapacity, LOAD_FACTOR);
    }

    public ConcurrentHashTable(int initialCapacity, float loadFactor) {
        if (initialCapacity <= 0) throw new IllegalArgumentException("Capacity must be > 0");
        if (loadFactor <= 0 || Float.isNaN(loadFactor)) throw new IllegalArgumentException("Invalid load factor");
        this.loadFactor = loadFactor;
        this.tableRef   = new AtomicReference<>(new AtomicReferenceArray<>(nextPowerOfTwo(initialCapacity)));
    }

    private static int spread(int h) {
        h ^= (h >>> 16);
        return h;
    }

    private static int bucketIndex(int hash, int len) {
        return hash & (len - 1);
    }

    private int hash(R row, C column) {
        return spread(row.hashCode() ^ column.hashCode());
    }

    @Override
    public V get(R row, C column) {
        int hash = hash(row, column);
        AtomicReferenceArray<Node<R, C, V>> table = tableRef.get();
        Node<R, C, V> node = table.get(bucketIndex(hash, table.length()));
        while (node != null) {
            if (node.hash == hash && Objects.equals(node.row, row) && Objects.equals(node.column, column))
                return node.value;
            node = node.next;
        }
        return null;
    }

    @Override
    public V put(R row, C column, V value) {
        return doPut(row, column, value, false);
    }

    @Override
    public V putIfAbsent(R row, C column, V value) {
        return doPut(row, column, value, true);
    }

    /**
     * Lock-free put.
     *
     * <p>Spin loop:
     * <ol>
     *   <li>Check if resize is needed and help if so.</li>
     *   <li>Walk the chain to find an existing node for (row, column).</li>
     *   <li>If found: rebuild the chain with an updated node and CAS the head.
     *       If CAS fails, retry from step 1 (another thread mutated this bucket).</li>
     *   <li>If not found: prepend a new node and CAS the head.
     *       If CAS fails, retry from step 1.</li>
     * </ol>
     */
    private V doPut(R row, C column, V value, boolean ifAbsent) {
        int hash = hash(row, column);
        while (true) {
            ensureCapacity();
            AtomicReferenceArray<Node<R, C, V>> table = tableRef.get();
            int idx = bucketIndex(hash, table.length());
            Node<R, C, V> head = table.get(idx);


            Node<R, C, V> node = head;
            while (node != null) {
                if (node.hash == hash && Objects.equals(node.row, row) && Objects.equals(node.column, column)) {
                    if (ifAbsent) return node.value;
                    // Rebuild chain replacing this node's value
                    Node<R, C, V> newHead = rebuildWithUpdate(head, node, row, column, value, hash);
                    if (table.compareAndSet(idx, head, newHead)) return node.value;
                    break;
                }
                node = node.next;
            }

            if (node != null) continue;

            Node<R, C, V> newNode = new Node<>(row, column, value, hash, head);
            if (table.compareAndSet(idx, head, newNode)) {
                size.increment();
                return null;
            }
        }
    }

    /**
     * Rebuilds the chain from {@code head}, replacing the matching node with
     * a new node carrying {@code newValue}. Non-matching nodes are shared
     * (immutable, so safe to reuse).
     */
    private Node<R, C, V> rebuildWithUpdate(
            Node<R, C, V> head, Node<R, C, V> target,
            R row, C column, V newValue, int hash
    ) {
        // Collect nodes before target, then re-prepend them after the updated node
        // to preserve chain order.
        Deque<Node<R, C, V>> before = rebuild(head, target);
        Node<R, C, V> rebuilt = new Node<>(row, column, newValue, hash, target.next);
        // Re-prepend nodes that came before target
        while (!before.isEmpty()) {
            Node<R, C, V> n = before.pop();
            rebuilt = new Node<>(n.row, n.column, n.value, n.hash, rebuilt);
        }
        return rebuilt;
    }

    private Deque<Node<R, C, V>> rebuild(Node<R, C, V> head, Node<R, C, V> target) {
        Deque<Node<R, C, V>> before = new ArrayDeque<>();
        Node<R, C, V> cur = head;
        while (cur != target) {
            before.push(cur);
            cur = cur.next;
        }
        return before;
    }

    /**
     * Lock-free remove.
     *
     * <p>Rebuilds the chain without the target node and CAS-swaps the head.
     * Retries if CAS fails.
     */
    @Override
    public V remove(R row, C column) {
        int hash = hash(row, column);
        while (true) {
            AtomicReferenceArray<Node<R, C, V>> table = tableRef.get();
            int idx = bucketIndex(hash, table.length());
            Node<R, C, V> head = table.get(idx);

            Node<R, C, V> node = head;
            while (node != null) {
                if (node.hash == hash && Objects.equals(node.row, row) && Objects.equals(node.column, column)) {
                    Node<R, C, V> newHead = rebuildWithout(head, node);
                    if (table.compareAndSet(idx, head, newHead)) {
                        size.decrement();
                        return node.value;
                    }
                    break;
                }
                node = node.next;
            }
            if (node == null) return null;
        }
    }

    @Override
    public V remove(R row, C column, V value) {
        V current = get(row, column);
        if (current != null && Objects.equals(current, value)) {
            return remove(row, column);
        }
        return current;
    }

    /** Rebuilds the chain omitting {@code target}. */
    private Node<R, C, V> rebuildWithout(Node<R, C, V> head, Node<R, C, V> target) {
        Deque<Node<R, C, V>> before = rebuild(head, target);
        Node<R, C, V> rebuilt = target.next; // skip target
        while (!before.isEmpty()) {
            Node<R, C, V> n = before.pop();
            rebuilt = new Node<>(n.row, n.column, n.value, n.hash, rebuilt);
        }
        return rebuilt;
    }

    private void ensureCapacity() {
        AtomicReferenceArray<Node<R, C, V>> table = tableRef.get();
        if (size.sum() < table.length() * loadFactor) return;

        // Only one thread resizes at a time; others spin until the new table is visible.
        if (!resizing.compareAndSet(false, true)) {
            // Spin-yield until the resizing thread publishes the new array
            while (resizing.get()) Thread.onSpinWait();
            return;
        }

        try {
            // Re-check after acquiring the resize guard (another thread may have just finished)
            table = tableRef.get();
            if (size.sum() < table.length() * loadFactor) return;

            int newLen = table.length() * 2;
            AtomicReferenceArray<Node<R, C, V>> newTable = new AtomicReferenceArray<>(newLen);

            for (int i = 0; i < table.length(); i++) {
                Node<R, C, V> node = table.get(i);
                while (node != null) {
                    int newIdx = bucketIndex(node.hash, newLen);
                    newTable.set(newIdx, new Node<>(node.row, node.column, node.value, node.hash, newTable.get(newIdx)));
                    node = node.next;
                }
            }

            tableRef.set(newTable);
        } finally {
            resizing.set(false);
        }
    }

    @Override
    public boolean containsKey(R row, C column) {
        return get(row, column) != null;
    }

    @Override
    public boolean containsValue(V value) {
        AtomicReferenceArray<Node<R, C, V>> table = tableRef.get();
        for (int i = 0; i < table.length(); i++) {
            Node<R, C, V> node = table.get(i);
            while (node != null) {
                if (Objects.equals(node.value, value)) return true;
                node = node.next;
            }
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
        AtomicReferenceArray<Node<R, C, V>> table = tableRef.get();
        for (int i = 0; i < table.length(); i++) {
            Node<R, C, V> node = table.get(i);
            while (node != null) {
                action.accept(node.row, node.column, node.value);
                node = node.next;
            }
        }
    }

    @Override
    public int size() {
        return (int) Math.min(size.sum(), Integer.MAX_VALUE);
    }

    @Override
    public boolean isEmpty() {
        return size.sum() == 0;
    }

    @Override
    public void clear() {
        AtomicReferenceArray<Node<R, C, V>> table = tableRef.get();
        for (int i = 0; i < table.length(); i++) table.set(i, null);
        size.reset();
    }

    @Override
    public Map<C, V> row(R row) {
        return new RowMap(row);
    }

    @Override
    public Set<Table.Entry<R, C, V>> entrySet() {
        if (entrySetView == null) entrySetView = new EntrySet();
        return entrySetView;
    }

    @Override
    public Set<Table.KeyEntry<R, C>> keySet() {
        if (keySetView == null) keySetView = new KeySet();
        return keySetView;
    }

    @Override
    public Collection<V> values() {
        if (valuesView == null) valuesView = new Values();
        return valuesView;
    }

    private abstract class TableIterator<T> implements Iterator<T> {
        // Snapshot of the array taken at iterator creation
        final AtomicReferenceArray<Node<R, C, V>> snapshot = tableRef.get();
        int bucketIndex = 0;
        Node<R, C, V> current = null;
        Node<R, C, V> lastReturned = null;

        TableIterator() {
            advance();
        }

        private void advance() {
            // Move current to the next non-null node across buckets
            if (current != null && current.next != null) {
                current = current.next;
                return;
            }
            current = null;
            while (bucketIndex < snapshot.length()) {
                Node<R, C, V> head = snapshot.get(bucketIndex++);
                if (head != null) {
                    current = head;
                    return;
                }
            }
        }

        @Override
        public boolean hasNext() {
            return current != null;
        }

        Node<R, C, V> nextNode() {
            if (current == null) throw new NoSuchElementException();
            lastReturned = current;
            advance();
            return lastReturned;
        }

        /**
         * Remove is best-effort in a weakly-consistent iterator — it delegates
         * to the live table's lock-free remove, not the snapshot.
         */
        @Override
        public void remove() {
            if (lastReturned == null) throw new IllegalStateException();
            ConcurrentHashTable.this.remove(lastReturned.row, lastReturned.column);
            lastReturned = null;
        }
    }

    private class EntrySet extends AbstractSet<Table.Entry<R, C, V>> {
        @Override public int size() { return ConcurrentHashTable.this.size(); }
        @Override public boolean contains(Object o) {
            if (!(o instanceof Table.Entry<?, ?, ?> e)) return false;
            return ConcurrentHashTable.this.containsKey((R) e.row(), (C) e.column());
        }
        @Override public boolean add(Table.Entry<R, C, V> e) {
            return ConcurrentHashTable.this.put(e.row(), e.column(), e.value()) == null;
        }
        @Override public boolean remove(Object o) {
            if (!(o instanceof Table.Entry<?, ?, ?> e)) return false;
            return ConcurrentHashTable.this.remove((R) e.row(), (C) e.column()) != null;
        }
        @Override public void clear() { ConcurrentHashTable.this.clear(); }
        @Override public @NotNull Iterator<Table.Entry<R, C, V>> iterator() {
            return new TableIterator<Table.Entry<R, C, V>>() {
                @Override public Table.Entry<R, C, V> next() { return nextNode(); }
            };
        }
    }

    private class KeySet extends AbstractSet<Table.KeyEntry<R, C>> {
        @Override public int size() { return ConcurrentHashTable.this.size(); }
        @Override public boolean contains(Object o) {
            if (!(o instanceof Table.KeyEntry<?, ?> e)) return false;
            return ConcurrentHashTable.this.containsKey((R) e.row(), (C) e.column());
        }
        @Override public boolean remove(Object o) {
            if (!(o instanceof Table.KeyEntry<?, ?> e)) return false;
            return ConcurrentHashTable.this.remove((R) e.row(), (C) e.column()) != null;
        }
        @Override public void clear() { ConcurrentHashTable.this.clear(); }
        @Override public @NotNull Iterator<Table.KeyEntry<R, C>> iterator() {
            return new TableIterator<Table.KeyEntry<R, C>>() {
                @Override public Table.KeyEntry<R, C> next() { return nextNode().key(); }
            };
        }
    }

    private class Values extends AbstractCollection<V> {
        @Override public int size() { return ConcurrentHashTable.this.size(); }
        @Override public boolean contains(Object o) { return ConcurrentHashTable.this.containsValue((V) o); }
        @Override public @NotNull Iterator<V> iterator() {
            return new TableIterator<V>() {
                @Override public V next() { return nextNode().value; }
            };
        }
    }

    public class RowMap implements Map<C, V> {
        private final R row;

        RowMap(R row) { this.row = row; }

        @Override
        public int size() {
            int count = 0;
            AtomicReferenceArray<Node<R, C, V>> table = tableRef.get();
            for (int i = 0; i < table.length(); i++) {
                Node<R, C, V> node = table.get(i);
                while (node != null) {
                    if (Objects.equals(node.row, row)) count++;
                    node = node.next;
                }
            }
            return count;
        }

        @Override
        public boolean isEmpty() {
            AtomicReferenceArray<Node<R, C, V>> table = tableRef.get();
            for (int i = 0; i < table.length(); i++) {
                Node<R, C, V> node = table.get(i);
                while (node != null) {
                    if (Objects.equals(node.row, row)) return false;
                    node = node.next;
                }
            }
            return true;
        }

        @Override public boolean containsKey(Object c) { return ConcurrentHashTable.this.containsKey(row, (C) c); }
        @Override public boolean containsValue(Object v) { return ConcurrentHashTable.this.containsValue((V) v); }
        @Override public V       get(Object c)       { return ConcurrentHashTable.this.get(row, (C) c); }
        @Override public V       put(C c, V v)       { return ConcurrentHashTable.this.put(row, c, v); }
        @Override public V       remove(Object c)    { return ConcurrentHashTable.this.remove(row, (C) c); }

        @Override
        public void clear() {
            AtomicReferenceArray<Node<R, C, V>> table = tableRef.get();
            for (int i = 0; i < table.length(); i++) {
                Node<R, C, V> head = table.get(i);
                Node<R, C, V> filtered = null;
                Node<R, C, V> cur = head;
                while (cur != null) {
                    if (!Objects.equals(cur.row, row)) {
                        filtered = new Node<>(cur.row, cur.column, cur.value, cur.hash, filtered);
                    } else {
                        size.decrement();
                    }
                    cur = cur.next;
                }
                table.set(i, filtered);
            }
        }

        @Override
        public void putAll(Map<? extends C, ? extends V> map) {
            for (Map.Entry<? extends C, ? extends V> e : map.entrySet())
                ConcurrentHashTable.this.put(row, e.getKey(), e.getValue());
        }

        @Override public @NotNull Set<C>              keySet()   { return new RowMapKeySet(row); }
        @Override public @NotNull Collection<V>       values()   { return new RowMapValues(row); }
        @Override public @NotNull Set<Map.Entry<C,V>> entrySet() { return new RowMapEntries(row); }
    }

    private abstract class RowTableIterator<T> implements Iterator<T> {
        final AtomicReferenceArray<Node<R, C, V>> snapshot = tableRef.get();
        final R row;
        int           bucketIdx = 0;
        Node<R, C, V> current   = null;
        Node<R, C, V> lastReturned = null;

        RowTableIterator(R row) { this.row = row; advance(); }

        private void advance() {
            // Skip to next node belonging to `row`
            while (true) {
                if (current != null && current.next != null) {
                    current = current.next;
                } else {
                    current = null;
                    if (bucketIdx >= snapshot.length()) return;
                    current = snapshot.get(bucketIdx++);
                }
                if (current == null) { if (bucketIdx >= snapshot.length()) return; continue; }
                if (Objects.equals(current.row, row)) return;
            }
        }

        @Override public boolean hasNext() { return current != null; }

        Node<R, C, V> nextNode() {
            if (current == null) throw new NoSuchElementException();
            lastReturned = current;
            advance();
            return lastReturned;
        }

        @Override
        public void remove() {
            if (lastReturned == null) throw new IllegalStateException();
            ConcurrentHashTable.this.remove(lastReturned.row, lastReturned.column);
            lastReturned = null;
        }
    }

    public class RowMapEntries extends AbstractSet<Map.Entry<C, V>> {
        private final R row;
        RowMapEntries(R row) { this.row = row; }
        @Override public int size() { return ConcurrentHashTable.this.size(); }
        @Override public boolean contains(Object o) {
            if (!(o instanceof Map.Entry<?,?> e)) return false;
            return ConcurrentHashTable.this.containsKey(row, (C) e.getKey());
        }
        @Override public boolean add(Map.Entry<C, V> e) {
            return ConcurrentHashTable.this.put(row, e.getKey(), e.getValue()) == null;
        }
        @Override public boolean remove(Object o) {
            if (!(o instanceof Map.Entry<?,?> e)) return false;
            return ConcurrentHashTable.this.remove(row, (C) e.getKey()) != null;
        }
        @Override public @NotNull Iterator<Map.Entry<C, V>> iterator() {
            return new RowTableIterator<Map.Entry<C, V>>(row) {
                @Override public Map.Entry<C, V> next() {
                    Node<R, C, V> n = nextNode();
                    return Map.entry(n.column, n.value);
                }
            };
        }
    }

    public class RowMapKeySet extends AbstractSet<C> {
        private final R row;
        RowMapKeySet(R row) { this.row = row; }
        @Override public int size() { return ConcurrentHashTable.this.size(); }
        @Override public boolean contains(Object o) { return ConcurrentHashTable.this.containsKey(row, (C) o); }
        @Override public boolean remove(Object o)   { return ConcurrentHashTable.this.remove(row, (C) o) != null; }
        @Override public @NotNull Iterator<C> iterator() {
            return new RowTableIterator<C>(row) {
                @Override public C next() { return nextNode().column; }
            };
        }
    }

    public class RowMapValues extends AbstractCollection<V> {
        private final R row;
        RowMapValues(R row) { this.row = row; }
        @Override public int size() { return ConcurrentHashTable.this.size(); }
        @Override public boolean contains(Object o) { return ConcurrentHashTable.this.containsValue((V) o); }
        @Override public @NotNull Iterator<V> iterator() {
            return new RowTableIterator<V>(row) {
                @Override public V next() { return nextNode().value; }
            };
        }
    }

    private static int nextPowerOfTwo(int n) {
        if (n <= 1) return 1;
        int p = 1;
        while (p < n) p <<= 1;
        return p;
    }
}