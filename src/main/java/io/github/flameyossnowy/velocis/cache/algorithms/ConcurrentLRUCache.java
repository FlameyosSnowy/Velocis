package io.github.flameyossnowy.velocis.cache.algorithms;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.StampedLock;

import static io.github.flameyossnowy.velocis.cache.algorithms.ConcurrentLFUCache.nextPow2;

/**
 * Concurrent LRU Cache — Structure-of-Arrays open-addressing hash table
 * guarded by a StampedLock, with LRU ordering tracked by the lock-free
 * ConcurrentDoublyLinkedList.
 * <p>
 * Layout (all arrays share the same slot index):
 *   int[]    hashes   — spread hash; 0 = empty, -1 = tombstone
 *   Object[] keys     — key at slot i
 *   Object[] values   — value at slot i
 *   Node[]   nodes    — corresponding DLL node for LRU tracking
 * <p>
 * Reads use optimistic stamps with fallback to a read lock.
 * Writes (put / remove / evict) take the write lock.
 * LRU list mutations (moveToEnd, removeHead) are lock-free via CAS inside
 * ConcurrentDoublyLinkedList — they happen outside the write lock where
 * possible to reduce contention.
 */
@SuppressWarnings("unchecked")
public class ConcurrentLRUCache<K, V> implements Map<K, V> {

    private static final int   EMPTY     =  0;
    private static final int   TOMBSTONE = -1;
    private static final float LOAD      = 0.70f;

    private final int[]    hashes;
    private final Object[] keys;
    private final Object[] values;
    private final ConcurrentDoublyLinkedList.Node<K, V>[] nodes;

    private final int capacity;
    private final int mask;
    private final int maxSize;

    private final StampedLock              lock      = new StampedLock();
    private final AtomicInteger            liveCount = new AtomicInteger(0);
    private final ConcurrentDoublyLinkedList<K, V> list = new ConcurrentDoublyLinkedList<>();

    private transient volatile KeySetView   keySetView;
    private transient volatile ValuesView   valuesView;
    private transient volatile EntrySetView entrySetView;

    public ConcurrentLRUCache(int maxSize) {
        if (maxSize <= 0) throw new IllegalArgumentException("maxSize must be > 0");
        this.maxSize  = maxSize;
        this.capacity = nextPow2((int) (maxSize / LOAD) + 1);
        this.mask     = capacity - 1;
        this.hashes   = new int[capacity];
        this.keys     = new Object[capacity];
        this.values   = new Object[capacity];
        this.nodes    = new ConcurrentDoublyLinkedList.Node[capacity];
    }

    public ConcurrentLRUCache()                                        { this(16); }
    public ConcurrentLRUCache(int maxSize, int ignored)                { this(maxSize); }
    public ConcurrentLRUCache(int maxSize, float ignored)              { this(maxSize); }
    public ConcurrentLRUCache(int maxSize, int ignored, float ignored2){ this(maxSize); }

    @Override
    public @Nullable V get(Object key) {
        int h = spread(key.hashCode());

        long stamp = lock.tryOptimisticRead();
        int idx = findSlot(h, key);
        V   val = idx >= 0 ? (V) values[idx] : null;
        ConcurrentDoublyLinkedList.Node<K, V> node = idx >= 0 ? nodes[idx] : null;

        if (!lock.validate(stamp)) {
            stamp = lock.readLock();
            try {
                idx  = findSlot(h, key);
                val  = idx >= 0 ? (V) values[idx] : null;
                node = idx >= 0 ? nodes[idx] : null;
            } finally {
                lock.unlockRead(stamp);
            }
        }

        if (node != null) list.moveToEnd(node);
        return val;
    }

    @Override
    public @Nullable V put(K key, V value) {
        if (value == null) throw new IllegalArgumentException("Null values are not allowed");
        int h = spread(key.hashCode());

        long stamp = lock.writeLock();
        try {
            int idx = findSlot(h, key);
            if (idx >= 0) {
                V old = (V) values[idx];
                values[idx] = value;
                ConcurrentDoublyLinkedList.Node<K, V> node = nodes[idx];
                // promote outside lock after we've finished mutating the table
                lock.unlockWrite(stamp);
                stamp = 0L;
                list.moveToEnd(node);
                return old;
            }

            if (liveCount.get() >= maxSize) evictLRU();

            int slot = probeInsert(h);
            ConcurrentDoublyLinkedList.Node<K, V> node = list.addToEnd(key, value);
            hashes[slot] = h;
            keys  [slot] = key;
            values[slot] = value;
            nodes [slot] = node;
            liveCount.incrementAndGet();
            return null;
        } finally {
            if (stamp != 0L) lock.unlockWrite(stamp);
        }
    }

    @Override
    public @Nullable V remove(Object key) {
        int h = spread(key.hashCode());
        long stamp = lock.writeLock();
        try {
            int idx = findSlot(h, key);
            if (idx < 0) return null;

            V old = (V) values[idx];
            ConcurrentDoublyLinkedList.Node<K, V> node = nodes[idx];

            hashes[idx] = TOMBSTONE;
            keys  [idx] = null;
            values[idx] = null;
            nodes [idx] = null;
            liveCount.decrementAndGet();

            // remove from LRU list outside table lock
            lock.unlockWrite(stamp);
            stamp = 0L;
            list.remove(node);
            return old;
        } finally {
            if (stamp != 0L) lock.unlockWrite(stamp);
        }
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> m) {
        m.forEach(this::put);
    }

    @Override
    public void clear() {
        long stamp = lock.writeLock();
        try {
            Arrays.fill(hashes, EMPTY);
            Arrays.fill(keys,   null);
            Arrays.fill(values, null);
            Arrays.fill(nodes,  null);
            liveCount.set(0);
            list.clear();
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    @Override public int     size()                   { return liveCount.get(); }
    @Override public boolean isEmpty()                { return liveCount.get() == 0; }
    @Override public boolean containsKey(Object key)  { return get(key) != null; }

    @Override
    public boolean containsValue(Object v) {
        long stamp = lock.readLock();
        try {
            for (int i = 0; i < capacity; i++)
                if (hashes[i] > 0 && v.equals(values[i])) return true;
            return false;
        } finally {
            lock.unlockRead(stamp);
        }
    }

    @Override public @NotNull Set<K>           keySet()   { return keySetView   == null ? (keySetView   = new KeySetView())   : keySetView;   }
    @Override public @NotNull Collection<V>    values()   { return valuesView   == null ? (valuesView   = new ValuesView())   : valuesView;   }
    @Override public @NotNull Set<Entry<K, V>> entrySet() { return entrySetView == null ? (entrySetView = new EntrySetView()) : entrySetView; }

    /** Evicts the least-recently-used entry. Caller must hold write lock. */
    private void evictLRU() {
        ConcurrentDoublyLinkedList.Node<K, V> lru = list.removeHead();
        if (lru == null) return;
        int h   = spread(lru.key.hashCode());
        int idx = findSlot(h, lru.key);
        if (idx < 0) return;
        hashes[idx] = TOMBSTONE;
        keys  [idx] = null;
        values[idx] = null;
        nodes [idx] = null;
        liveCount.decrementAndGet();
    }

    /** Linear probe — returns slot if key found, -1 otherwise. */
    private int findSlot(int h, Object key) {
        int i = h & mask;
        for (int probe = 0; probe <= mask; probe++) {
            int sh = hashes[i];
            if (sh == EMPTY) return -1;
            if (sh == h && key.equals(keys[i])) return i;
            i = (i + 1) & mask;
        }
        return -1;
    }

    /** Linear probe — returns first empty or tombstone slot for insertion. */
    private int probeInsert(int h) {
        return ConcurrentLFRUCache.probe0(h, hashes, mask, EMPTY, TOMBSTONE);
    }

    private static int spread(int h) {
        h ^= (h >>> 16);
        h &= 0x7FFF_FFFF;
        return h == 0 ? 1 : h;
    }

    private abstract class SlotIterator<T> implements Iterator<T> {
        int cursor = 0;
        int current = -1;

        SlotIterator() { advance(); }

        private void advance() {
            while (cursor < capacity && hashes[cursor] <= 0) cursor++;
            current = cursor < capacity ? cursor++ : -1;
        }

        @Override public boolean hasNext() { return current >= 0; }

        @Override public T next() {
            if (current < 0) throw new NoSuchElementException();
            int i = current;
            advance();
            return extract(i);
        }

        abstract T extract(int i);
    }

    private final class KeySetView extends AbstractSet<K> {
        @Override public int  size()              { return liveCount.get(); }
        @Override public boolean contains(Object o) { return containsKey(o); }
        @Override public boolean remove(Object o)   { return ConcurrentLRUCache.this.remove(o) != null; }
        @Override public void clear()               { ConcurrentLRUCache.this.clear(); }
        @Override public @NotNull Iterator<K> iterator() {
            long stamp = lock.readLock();
            try { return new SlotIterator<K>() { @Override K extract(int i) { return (K) keys[i]; } }; }
            finally { lock.unlockRead(stamp); }
        }
    }

    private final class ValuesView extends AbstractCollection<V> {
        @Override public int  size()              { return liveCount.get(); }
        @Override public boolean contains(Object o) { return containsValue(o); }
        @Override public void clear()               { ConcurrentLRUCache.this.clear(); }
        @Override public @NotNull Iterator<V> iterator() {
            long stamp = lock.readLock();
            try { return new SlotIterator<V>() { @Override V extract(int i) { return (V) values[i]; } }; }
            finally { lock.unlockRead(stamp); }
        }
    }

    private final class EntrySetView extends AbstractSet<Entry<K, V>> {
        @Override public int  size()              { return liveCount.get(); }
        @Override public void clear()             { ConcurrentLRUCache.this.clear(); }
        @Override public boolean contains(Object o) {
            if (!(o instanceof Map.Entry<?, ?> e)) return false;
            V v = get(e.getKey());
            return v != null && v.equals(e.getValue());
        }
        @Override public boolean remove(Object o) {
            if (!(o instanceof Map.Entry<?, ?> e)) return false;
            return ConcurrentLRUCache.this.remove(e.getKey()) != null;
        }
        @Override public @NotNull Iterator<Entry<K, V>> iterator() {
            long stamp = lock.readLock();
            try {
                return new SlotIterator<Entry<K, V>>() {
                    @Override Entry<K, V> extract(int i) {
                        return Map.entry((K) keys[i], (V) values[i]);
                    }
                };
            } finally { lock.unlockRead(stamp); }
        }
    }
}