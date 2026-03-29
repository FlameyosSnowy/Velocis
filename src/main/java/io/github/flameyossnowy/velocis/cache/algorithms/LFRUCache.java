package io.github.flameyossnowy.velocis.cache.algorithms;

import io.github.flameyossnowy.velocis.cache.utils.CountMinSketch;

import java.util.*;

public class LFRUCache<K, V> implements Map<K, V> {

    private static final Object EMPTY     = null;
    private static final Object TOMBSTONE = new Object();

    private Object[] keys;
    private Object[] values;
    private int[]    hashes;

    private final TreeMap<Integer, FrequencyList> frequencyBuckets;

    private final Map<Integer, FrequencyList.Node> slotToNode;

    private final int               capacity;
    private int                     size;
    private final CountMinSketch<K> sketch;

    private static final int DEFAULT_CAPACITY = 16;

    private EntrySet entrySetView;
    private Values   valuesView;
    private KeySet   keySetView;

    private static class FrequencyList {
        static class Node {
            int  slot;
            Node prev, next;
            Node(int slot) { this.slot = slot; }
        }

        Node head, tail;

        /** Appends to tail (most-recently-used end). */
        Node addSlot(int slot) {
            Node node = new Node(slot);
            if (head == null) {
                head = tail = node;
            } else {
                tail.next = node;
                node.prev = tail;
                tail = node;
            }
            return node;
        }

        void removeNode(Node node) {
            if (node.prev != null) node.prev.next = node.next; else head = node.next;
            if (node.next != null) node.next.prev = node.prev; else tail = node.prev;
            node.prev = node.next = null;
        }

        /** Removes and returns the head slot (least-recently-used at this frequency). */
        int removeHead() {
            if (head == null) return -1;
            Node node = head;
            removeNode(node);
            return node.slot;
        }

        boolean isEmpty() { return head == null; }
    }

    public LFRUCache(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Cache size must be greater than 0");
        int tableSize   = nextPowerOfTwo(capacity * 2);
        this.capacity   = capacity;
        this.keys       = new Object[tableSize];
        this.values     = new Object[tableSize];
        this.hashes     = new int[tableSize];
        this.sketch     = new CountMinSketch<>();
        this.frequencyBuckets = new TreeMap<>();
        this.slotToNode = new HashMap<>();
    }

    public LFRUCache() {
        this(DEFAULT_CAPACITY);
    }

    @Override
    @SuppressWarnings("unchecked")
    public V get(Object key) {
        int slot = findSlot(key);
        if (slot < 0) return null;
        updateFrequency((K) key, slot);
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
            updateFrequency(key, slot);
            return old;
        }

        if (size >= capacity) evictLFRU();

        slot = insertNew(key, value);
        // New entries start at frequency 1
        sketch.increment(key);
        addToFrequencyBucket(slot, 1);
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public V remove(Object key) {
        int slot = findSlot(key);
        if (slot < 0) return null;

        V old = (V) values[slot];
        removeFromFrequencyBucket(slot);

        keys[slot]   = TOMBSTONE;
        values[slot] = EMPTY;
        hashes[slot] = 0;
        slotToNode.remove(slot);
        size--;
        return old;
    }

    @Override
    public boolean containsKey(Object key)   { return findSlot(key) >= 0; }

    @Override
    public boolean containsValue(Object target) {
        for (int i = 0; i < keys.length; i++)
            if (isOccupied(i) && values[i].equals(target)) return true;
        return false;
    }

    @Override public int     size()    { return size; }
    @Override public boolean isEmpty() { return size == 0; }

    @Override
    public void putAll(Map<? extends K, ? extends V> map) {
        for (Entry<? extends K, ? extends V> e : map.entrySet()) put(e.getKey(), e.getValue());
    }

    @Override
    public void clear() {
        Arrays.fill(keys,   EMPTY);
        Arrays.fill(values, EMPTY);
        Arrays.fill(hashes, 0);
        frequencyBuckets.clear();
        slotToNode.clear();
        sketch.clear();
        size = 0;
    }

    @Override public Set<K>            keySet()   { return (keySetView  == null) ? (keySetView  = new KeySet())   : keySetView;  }
    @Override public Collection<V>     values()   { return (valuesView  == null) ? (valuesView  = new Values())   : valuesView;  }
    @Override public Set<Entry<K, V>>  entrySet() { return (entrySetView == null) ? (entrySetView = new EntrySet()) : entrySetView; }

    @SuppressWarnings("unchecked")
    private void updateFrequency(K key, int slot) {
        removeFromFrequencyBucket(slot);
        sketch.increment(key);
        int newFreq = sketch.getFrequency(key);
        addToFrequencyBucket(slot, newFreq);
    }

    private void addToFrequencyBucket(int slot, int frequency) {
        FrequencyList list = frequencyBuckets.computeIfAbsent(frequency, ignored -> new FrequencyList());
        FrequencyList.Node node = list.addSlot(slot);
        slotToNode.put(slot, node);
    }

    private void removeFromFrequencyBucket(int slot) {
        FrequencyList.Node node = slotToNode.remove(slot);
        if (node == null) return;

        // Find which bucket this node belongs to by scanning, O(log n) at most
        // since we only have as many buckets as distinct frequencies
        for (Map.Entry<Integer, FrequencyList> entry : frequencyBuckets.entrySet()) {
            FrequencyList list = entry.getValue();
            // Check if this node is actually in this list by walking from it
            // (safe because node.prev/next are nulled on removeNode)
            list.removeNode(node);
            if (list.isEmpty()) {
                frequencyBuckets.remove(entry.getKey());
            }
            break; // node can only belong to one bucket
        }
    }

    @SuppressWarnings("unchecked")
    private void evictLFRU() {
        if (frequencyBuckets.isEmpty()) return;

        int lowestFreq = frequencyBuckets.firstKey();
        FrequencyList list = frequencyBuckets.get(lowestFreq);
        if (list == null) return;

        int evictSlot = list.removeHead();
        if (list.isEmpty()) frequencyBuckets.remove(lowestFreq);

        if (evictSlot >= 0) {
            slotToNode.remove(evictSlot);
            keys[evictSlot]   = TOMBSTONE;
            values[evictSlot] = EMPTY;
            hashes[evictSlot] = 0;
            size--;
        }
    }

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

    /** Inserts a new key-value pair and returns the slot it landed in. */
    private int insertNew(K key, V value) {
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
        return slot;
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

    private abstract class TableIterator<T> implements Iterator<T> {
        int cursor      = -1;
        int lastReturned = -1;

        TableIterator() { advance(); }

        private void advance() {
            cursor = lastReturned + 1;
            while (cursor < keys.length && !isOccupied(cursor)) cursor++;
        }

        @Override public boolean hasNext() { return cursor < keys.length; }

        int nextSlot() {
            if (!hasNext()) throw new NoSuchElementException();
            lastReturned = cursor;
            advance();
            return lastReturned;
        }

        @Override
        public void remove() {
            if (lastReturned < 0) throw new IllegalStateException();
            removeFromFrequencyBucket(lastReturned);
            keys[lastReturned]   = TOMBSTONE;
            values[lastReturned] = EMPTY;
            hashes[lastReturned] = 0;
            slotToNode.remove(lastReturned);
            size--;
            lastReturned = -1;
        }
    }

    private class KeySet extends AbstractSet<K> {
        @Override public int size() { return size; }
        @Override public boolean contains(Object o) { return findSlot(o) >= 0; }
        @Override public Iterator<K> iterator() {
            return new TableIterator<K>() {
                @Override @SuppressWarnings("unchecked")
                public K next() { return (K) keys[nextSlot()]; }
            };
        }
    }

    private class Values extends AbstractCollection<V> {
        @Override public int size() { return size; }
        @Override public Iterator<V> iterator() {
            return new TableIterator<V>() {
                @Override @SuppressWarnings("unchecked")
                public V next() { return (V) values[nextSlot()]; }
            };
        }
    }

    private class EntrySet extends AbstractSet<Entry<K, V>> {
        @Override public int size() { return size; }
        @Override public Iterator<Entry<K, V>> iterator() {
            return new TableIterator<Entry<K, V>>() {
                @Override @SuppressWarnings("unchecked")
                public Entry<K, V> next() {
                    int slot = nextSlot();
                    return new AbstractMap.SimpleEntry<>((K) keys[slot], (V) values[slot]);
                }
            };
        }
        @Override public boolean contains(Object o) {
            if (!(o instanceof Entry<?, ?> e)) return false;
            int slot = findSlot(e.getKey());
            return slot >= 0 && values[slot].equals(e.getValue());
        }
    }
}