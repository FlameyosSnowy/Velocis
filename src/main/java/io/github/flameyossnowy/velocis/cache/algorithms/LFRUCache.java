package io.github.flameyossnowy.velocis.cache.algorithms;

import java.util.*;

public class LFRUCache<K, V> implements Map<K, V> {
    private EntrySet entrySet;
    private Values values;

    private static class Node<K, V> implements Map.Entry<K, V> {
        K key;
        V value;
        int frequency;
        Node<K, V> prev, next;

        Node(K key, V value) {
            this.key = key;
            this.value = value;
            this.frequency = 1;
        }

        @Override
        public K getKey() { return key; }

        @Override
        public V getValue() { return value; }

        @Override
        public V setValue(V value) {
            V oldValue = this.value;
            this.value = value;
            return oldValue;
        }
    }

    private static class DoublyLinkedList<K, V> {
        private Node<K, V> head, tail;

        void addNode(Node<K, V> node) {
            if (head == null) {
                head = tail = node;
            } else {
                tail.next = node;
                node.prev = tail;
                tail = node;
            }
        }

        void removeNode(Node<K, V> node) {
            if (node.prev != null) node.prev.next = node.next;
            if (node.next != null) node.next.prev = node.prev;
            if (node == head) head = node.next;
            if (node == tail) tail = node.prev;
        }

        boolean isEmpty() {
            return head == null;
        }

        Node<K, V> removeHead() {
            if (head == null) return null;
            Node<K, V> node = head;
            removeNode(node);
            return node;
        }
    }

    private final int capacity;
    private final Map<K, Map.Entry<K, V>> cache;
    private final TreeMap<Integer, DoublyLinkedList<K, V>> frequencyBuckets;

    private static final int DEFAULT_CAPACITY = 16;

    public LFRUCache(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Cache size must be greater than 0");
        this.capacity = capacity;
        this.cache = new HashMap<>();
        this.frequencyBuckets = new TreeMap<>();
    }

    public LFRUCache() {
        this(DEFAULT_CAPACITY);
    }

    public V get(Object key) {
        Node<K, V> node = (Node<K, V>) cache.get(key);
        if (node == null) return null;
        updateFrequency(node);
        return node.value;
    }

    public V put(K key, V value) {
        Node<K, V> node = (Node<K, V>) cache.get(key);
        if (node != null) {
            node.value = value;
            updateFrequency(node);
            return node.value;
        }
        if (cache.size() >= capacity) evictLFRU();
        Node<K, V> newNode = new Node<>(key, value);
        cache.put(key, newNode);
        addToFrequencyBucket(newNode);
        return null;
    }

    public V remove(Object key) {
        Node<K, V> node = (Node<K, V>) cache.remove(key);
        if (node == null) return null;
        removeFromFrequencyBucket(node);
        return node.value;
    }

    @Override
    public void putAll(final Map<? extends K, ? extends V> map) {
        for (Entry<? extends K, ? extends V> entry : map.entrySet()) {
            this.put(entry.getKey(), entry.getValue());
        }
    }

    @Override
    public void clear() {
        cache.clear();
        frequencyBuckets.clear();
    }

    @Override
    public Set<K> keySet() {
        return cache.keySet();
    }

    @Override
    public Collection<V> values() {
        return (this.values == null) ? (this.values = new Values()) : this.values;
    }

    @Override
    public Set<Entry<K, V>> entrySet() {
        return (this.entrySet == null) ? (this.entrySet = new EntrySet()) : this.entrySet;
    }

    private void updateFrequency(Node<K, V> node) {
        removeFromFrequencyBucket(node);
        node.frequency++;
        addToFrequencyBucket(node);
    }

    private void addToFrequencyBucket(Node<K, V> node) {
        frequencyBuckets.computeIfAbsent(node.frequency, k -> new DoublyLinkedList<>()).addNode(node);
    }

    private void removeFromFrequencyBucket(Node<K, V> node) {
        DoublyLinkedList<K, V> list = frequencyBuckets.get(node.frequency);
        if (list != null) {
            list.removeNode(node);
            if (list.isEmpty()) frequencyBuckets.remove(node.frequency);
        }
    }

    private void evictLFRU() {
        if (frequencyBuckets.isEmpty()) return;
        int lowestFrequency = frequencyBuckets.firstKey();
        DoublyLinkedList<K, V> list = frequencyBuckets.get(lowestFrequency);
        if (list != null) {
            Node<K, V> nodeToEvict = list.removeHead();
            if (nodeToEvict != null) {
                cache.remove(nodeToEvict.key);
                if (list.isEmpty()) frequencyBuckets.remove(lowestFrequency);
            }
        }
    }

    public int size() {
        return cache.size();
    }

    @Override
    public boolean isEmpty() {
        return cache.isEmpty();
    }

    @Override
    public boolean containsValue(final Object o) {
        return cache.containsValue(o);
    }

    public boolean containsKey(Object key) {
        return cache.containsKey(key);
    }

    private class Values implements Collection<V> {
        @Override
        public int size() {
            return LFRUCache.this.cache.size();
        }

        @Override
        public boolean contains(Object o) {
            return LFRUCache.this.cache.containsValue(o);
        }

        @Override
        public boolean containsAll(Collection<?> c) {
            for (Object o : c) {
                if (!(o instanceof Map.Entry<?, ?> entry)) {
                    return false;
                }
                if (!LFRUCache.this.cache.containsValue(entry.getValue())) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public boolean addAll(final Collection<? extends V> collection) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean removeAll(final Collection<?> collection) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean retainAll(final Collection<?> collection) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void clear() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isEmpty() {
            return LFRUCache.this.cache.isEmpty();
        }

        @Override
        public Iterator<V> iterator() {
            return new ValuesIterator();
        }

        @Override
        public Object[] toArray() {
            return LFRUCache.this.cache.values().toArray();
        }

        @Override
        public <T> T[] toArray(T[] a) {
            return LFRUCache.this.cache.values().toArray(a);
        }

        @Override
        public boolean add(final V v) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean remove(final Object o) {
            throw new UnsupportedOperationException();
        }
    }

    private class ValuesIterator implements Iterator<V> {
        private final Iterator<Map.Entry<K, V>> iterator = LFRUCache.this.cache.values().iterator();

        @Override
        public boolean hasNext() {
            return iterator.hasNext();
        }

        @Override
        public V next() {
            return iterator.next().getValue();
        }

        @Override
        public void remove() {
            iterator.remove();
        }
    }

    private class EntrySet implements Set<Entry<K, V>> {
        @Override
        public int size() {
            return LFRUCache.this.cache.size();
        }

        @Override
        public boolean contains(Object o) {
            return LFRUCache.this.cache.containsValue(((Map.Entry<K, V>) o).getValue());
        }

        @Override
        public boolean containsAll(Collection<?> c) {
            return LFRUCache.this.cache.containsValue(c);
        }

        @Override
        public boolean addAll(final Collection<? extends Entry<K, V>> collection) {
            boolean modified = false;
            for (Entry<K, V> entry : collection) {
                modified |= LFRUCache.this.put(entry.getKey(), entry.getValue()) == null;
            }
            return modified;
        }

        @Override
        public boolean retainAll(final Collection<?> collection) {
            boolean modified = false;
            for (Object o : collection) {
                if (!(o instanceof Map.Entry<?, ?> entry)) {
                    continue;
                }
                if (collection.contains(LFRUCache.this.get(entry.getKey()))) {
                    continue;
                }
                modified |= LFRUCache.this.remove(entry.getKey()) != null;
            }
            return modified;
        }

        @Override
        public boolean removeAll(final Collection<?> collection) {
            boolean modified = false;
            for (Object o : collection) {
                if (!(o instanceof Map.Entry<?, ?> entry)) {
                    continue;
                }
                modified |= LFRUCache.this.remove(entry.getKey()) != null;
            }
            return modified;
        }

        @Override
        public void clear() {
            LFRUCache.this.clear();
        }

        @Override
        public boolean isEmpty() {
            return LFRUCache.this.cache.isEmpty();
        }

        @Override
        public Iterator<Entry<K, V>> iterator() {
            return LFRUCache.this.cache.values().iterator();
        }

        @Override
        public Object[] toArray() {
            return LFRUCache.this.cache.values().toArray();
        }

        @Override
        public <T> T[] toArray(final T[] ts) {
            return LFRUCache.this.cache.values().toArray(ts);
        }

        @Override
        public boolean add(final Entry<K, V> kvEntry) {
            return LFRUCache.this.put(kvEntry.getKey(), kvEntry.getValue()) == null;
        }

        @Override
        public boolean remove(final Object o) {
            Map.Entry<K, V> entry = (Map.Entry<K, V>) o;
            return LFRUCache.this.remove(entry.getKey()) != null;
        }
    }
}
