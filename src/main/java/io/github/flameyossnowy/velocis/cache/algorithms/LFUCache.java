package io.github.flameyossnowy.velocis.cache.algorithms;

import io.github.flameyossnowy.velocis.cache.utils.CountMinSketch;

import java.util.*;

public class LFUCache<K, V> implements Map<K, V> {
    private static final Object EMPTY     = null;
    private static final Object TOMBSTONE = new Object();

    private Object[] keys;
    private Object[] values;
    private int[]    hashes;

    private final int              capacity;
    private int                    size;
    private final CountMinSketch<K> sketch;

    private static final int DEFAULT_CAPACITY = 16;

    private EntrySet  entrySet;
    private KeySet    keySet;
    private Values    valuesView;

    public LFUCache(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Cache size must be greater than 0");
        // Allocate table larger than capacity so load factor stays low enough
        // for linear probing to perform well. Next power-of-two >= capacity * 2.
        int tableSize = nextPowerOfTwo(capacity * 2);
        this.capacity = capacity;
        this.keys     = new Object[tableSize];
        this.values   = new Object[tableSize];
        this.hashes   = new int[tableSize];
        this.sketch   = new CountMinSketch<>();
    }

    public LFUCache() {
        this(DEFAULT_CAPACITY);
    }

    @Override
    @SuppressWarnings("unchecked")
    public V get(Object key) {
        int slot = findSlot(key);
        if (slot < 0) return null;
        sketch.increment((K) key);
        return (V) values[slot];
    }

    @Override
    @SuppressWarnings("unchecked")
    public V put(K key, V value) {
        if (key   == null) throw new IllegalArgumentException("Null keys are not allowed");
        if (value == null) throw new IllegalArgumentException("Null values are not allowed");

        int slot = findSlot(key);

        if (slot >= 0) {
            V old = (V) values[slot];
            values[slot] = value;
            sketch.increment(key);
            return old;
        }

        if (size >= capacity) evictLFU();

        insertNew(key, value);
        sketch.increment(key);
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public V remove(Object key) {
        int slot = findSlot(key);
        if (slot < 0) return null;

        V old = (V) values[slot];

        keys[slot]   = TOMBSTONE;
        values[slot] = EMPTY;
        hashes[slot] = 0;
        size--;
        return old;
    }

    @Override
    public boolean containsKey(Object key) {
        return findSlot(key) >= 0;
    }

    @Override
    public boolean containsValue(Object target) {
        for (int i = 0; i < keys.length; i++) {
            if (isOccupied(i) && values[i].equals(target)) return true;
        }
        return false;
    }

    @Override
    public int size()      { return size; }

    @Override
    public boolean isEmpty() { return size == 0; }

    @Override
    public void putAll(Map<? extends K, ? extends V> map) {
        for (Entry<? extends K, ? extends V> e : map.entrySet()) put(e.getKey(), e.getValue());
    }

    @Override
    public void clear() {
        Arrays.fill(keys,   EMPTY);
        Arrays.fill(values, EMPTY);
        Arrays.fill(hashes, 0);
        sketch.clear();
        size = 0;
    }

    @Override
    public Set<K> keySet() {
        return (keySet == null) ? (keySet = new KeySet()) : keySet;
    }

    @Override
    public Collection<V> values() {
        return (valuesView == null) ? (valuesView = new Values()) : valuesView;
    }

    @Override
    public Set<Entry<K, V>> entrySet() {
        return (entrySet == null) ? (entrySet = new EntrySet()) : entrySet;
    }

    /** Returns the slot index for {@code key}, or {@code -1} if not present. */
    private int findSlot(Object key) {
        int hash     = key.hashCode();
        int tableLen = keys.length;
        int slot     = indexFor(hash, tableLen);

        for (int i = 0; i < tableLen; i++) {
            Object k = keys[slot];
            if (k == EMPTY)     return -1;
            if (k != TOMBSTONE && hashes[slot] == hash && k.equals(key)) return slot;
            slot = (slot + 1) & (tableLen - 1);
        }
        return -1;
    }

    /** Inserts a brand-new key-value pair. Caller must ensure capacity. */
    private void insertNew(K key, V value) {
        int hash     = key.hashCode();
        int tableLen = keys.length;
        int slot     = indexFor(hash, tableLen);

        while (keys[slot] != EMPTY && keys[slot] != TOMBSTONE) {
            slot = (slot + 1) & (tableLen - 1);
        }

        keys[slot]   = key;
        values[slot] = value;
        hashes[slot] = hash;
        size++;
    }

    /**
     * Scans all occupied slots and evicts the one with the lowest
     * Count-Min Sketch frequency estimate.
     */
    @SuppressWarnings("unchecked")
    private void evictLFU() {
        int minFreq = Integer.MAX_VALUE;
        int minSlot = -1;

        for (int i = 0; i < keys.length; i++) {
            if (!isOccupied(i)) continue;
            int freq = sketch.getFrequency((K) keys[i]);
            if (freq < minFreq) {
                minFreq = freq;
                minSlot = i;
            }
        }

        if (minSlot >= 0) {
            keys[minSlot]   = TOMBSTONE;
            values[minSlot] = EMPTY;
            hashes[minSlot] = 0;
            size--;
        }
    }

    private boolean isOccupied(int slot) {
        return keys[slot] != EMPTY && keys[slot] != TOMBSTONE;
    }

    private static int indexFor(int hash, int tableLen) {
        return (hash ^ (hash >>> 16)) & (tableLen - 1);
    }

    private static int nextPowerOfTwo(int n) {
        if (n <= 1) return 1;
        int p = 1;
        while (p < n) p <<= 1;
        return p;
    }

    /** Shared base for the three view iterators. */
    private abstract class TableIterator<T> implements Iterator<T> {
        int cursor = -1;
        int lastReturned = -1;

        TableIterator() { advance(); }

        private void advance() {
            cursor = lastReturned + 1;
            while (cursor < keys.length && !isOccupied(cursor)) cursor++;
        }

        @Override
        public boolean hasNext() { return cursor < keys.length; }

        int nextSlot() {
            if (!hasNext()) throw new NoSuchElementException();
            lastReturned = cursor;
            advance();
            return lastReturned;
        }

        @Override
        public void remove() {
            if (lastReturned < 0) throw new IllegalStateException();
            keys[lastReturned]   = TOMBSTONE;
            values[lastReturned] = EMPTY;
            hashes[lastReturned] = 0;
            size--;
            lastReturned = -1;
        }
    }

    private class KeySet extends AbstractSet<K> {
        @Override public int size() { return size; }

        @Override
        @SuppressWarnings("unchecked")
        public boolean contains(Object o) { return findSlot(o) >= 0; }

        @Override
        public Iterator<K> iterator() {
            return new TableIterator<K>() {
                @Override @SuppressWarnings("unchecked")
                public K next() { return (K) keys[nextSlot()]; }
            };
        }
    }

    private class Values extends AbstractCollection<V> {
        @Override public int size() { return size; }

        @Override
        public Iterator<V> iterator() {
            return new TableIterator<V>() {
                @Override @SuppressWarnings("unchecked")
                public V next() { return (V) values[nextSlot()]; }
            };
        }
    }

    private class EntrySet extends AbstractSet<Entry<K, V>> {
        @Override public int size() { return size; }

        @Override
        public Iterator<Entry<K, V>> iterator() {
            return new TableIterator<Entry<K, V>>() {
                @Override @SuppressWarnings("unchecked")
                public Entry<K, V> next() {
                    int slot = nextSlot();
                    K k = (K) keys[slot];
                    V v = (V) values[slot];
                    return new AbstractMap.SimpleEntry<>(k, v);
                }
            };
        }

        @Override
        public boolean contains(Object o) {
            if (!(o instanceof Entry<?, ?> e)) return false;
            int slot = findSlot(e.getKey());
            return slot >= 0 && values[slot].equals(e.getValue());
        }
    }
}