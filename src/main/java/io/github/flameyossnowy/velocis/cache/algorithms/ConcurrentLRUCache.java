package io.github.flameyossnowy.velocis.cache.algorithms;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;

/**
 * Raw LRU Cache implementation using a HashMap and Doubly Linked List.
 * Does NOT rely on LinkedHashMap's accessOrder feature.
 */
public class ConcurrentLRUCache<K, V> implements Map<K, V> {
    private final int maxSize;
    private final Map<K, Map.Entry<K, V>> cache;
    private final ConcurrentDoublyLinkedList<K, V> list;

    private Values values;
    private EntrySet entrySet;

    private static final int INITIAL_CONCURRENCY_LEVEL = 1;
    private static final int INITIAL_CAPACITY = 16;
    private static final float INITIAL_LOAD_FACTOR =  0.75F;

    public ConcurrentLRUCache(int maxSize, int concurrencyLevel, float loadFactor) {
        if (maxSize <= 0) throw new IllegalArgumentException("Cache size must be greater than 0");
        this.maxSize = maxSize;
        this.cache = new ConcurrentHashMap<>(concurrencyLevel, loadFactor);
        this.list = new ConcurrentDoublyLinkedList<>();
    }

    public ConcurrentLRUCache(int maxSize, int concurrencyLevel) {
        this(maxSize, concurrencyLevel, INITIAL_LOAD_FACTOR);
    }

    public ConcurrentLRUCache(int maxSize, float loadFactor) {
        this(maxSize, INITIAL_CONCURRENCY_LEVEL, loadFactor);
    }

    public ConcurrentLRUCache(int maxSize) {
        this(maxSize, INITIAL_CONCURRENCY_LEVEL, INITIAL_LOAD_FACTOR);
    }

    public ConcurrentLRUCache() {
        this(INITIAL_CAPACITY, INITIAL_CONCURRENCY_LEVEL, INITIAL_LOAD_FACTOR);
    }

    @Override
    public V get(Object key) {
        if (!cache.containsKey(key)) return null;

        Node<K, V> node = (Node<K, V>) cache.get(key);
        list.moveToEnd(node);  // Mark as most recently used
        return node.value;
    }

    @Override
    public V put(K key, V value) {
        if (value == null) throw new IllegalArgumentException("Null values are not allowed");

        if (cache.containsKey(key)) {
            Node<K, V> node = (Node<K, V>) cache.get(key);
            V temp = node.value;
            node.value = value;
            list.moveToEnd(node);
            return temp;
        }

        if (cache.size() >= maxSize) evictLRU();

        Node<K, V> newNode = list.addToEnd(key, value);
        cache.put(key, newNode);
        return null;
    }

    @Override
    public V remove(Object key) {
        Node<K, V> node = (Node<K, V>) cache.remove(key);
        list.remove(node);
        return node == null ? null : node.value;
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
        list.clear();
    }

    @Override
    public Set<K> keySet() {
        return cache.keySet();
    }

    @Override
    public Collection<V> values() {
        return (values == null) ? (values = new Values()) : values;
    }

    @Override
    public Set<Entry<K, V>> entrySet() {
        return entrySet == null ? (entrySet = new EntrySet()) : entrySet;
    }

    @Override
    public int size() {
        return cache.size();
    }

    @Override
    public boolean containsKey(Object key) {
        return cache.containsKey(key);
    }

    @Override
    public boolean containsValue(final Object o) {
        return cache.containsValue(o);
    }

    @Override
    public boolean isEmpty() {
        return cache.isEmpty();
    }

    private void evictLRU() {
        Node<K, V> lruNode = list.removeHead();
        if (lruNode != null) cache.remove(lruNode.key);
    }

    private class Values implements Collection<V> {
        @Override
        public int size() {
            return ConcurrentLRUCache.this.cache.size();
        }

        @Override
        public boolean contains(Object o) {
            return ConcurrentLRUCache.this.cache.containsValue(o);
        }

        @Override
        public boolean containsAll(Collection<?> c) {
            return ConcurrentLRUCache.this.cache.containsValue(c);
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
            return ConcurrentLRUCache.this.cache.isEmpty();
        }

        @Override
        public Iterator<V> iterator() {
            return new ValuesIterator();
        }

        @Override
        public Object[] toArray() {
            return ConcurrentLRUCache.this.cache.values().toArray();
        }

        @Override
        public <T> T[] toArray(T[] a) {
            return ConcurrentLRUCache.this.cache.values().toArray(a);
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
        private final Iterator<Map.Entry<K, V>> iterator = ConcurrentLRUCache.this.cache.values().iterator();

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
            return ConcurrentLRUCache.this.cache.size();
        }

        @Override
        public boolean contains(Object o) {
            return ConcurrentLRUCache.this.cache.containsValue(((Map.Entry<K, V>) o).getValue());
        }

        @Override
        public boolean containsAll(Collection<?> c) {
            for (Object o : c) {
                if (!(o instanceof Map.Entry<?, ?> entry)) {
                    return false;
                }
                if (!ConcurrentLRUCache.this.cache.containsValue(entry.getValue())) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public boolean addAll(final Collection<? extends Entry<K, V>> collection) {
            boolean modified = false;
            for (Entry<K, V> entry : collection) {
                modified |= ConcurrentLRUCache.this.put(entry.getKey(), entry.getValue()) == null;
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
                if (collection.contains(ConcurrentLRUCache.this.get(entry.getKey()))) {
                    continue;
                }
                modified |= ConcurrentLRUCache.this.remove(entry.getKey()) != null;
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
                modified |= ConcurrentLRUCache.this.remove(entry.getKey()) != null;
            }
            return modified;
        }

        @Override
        public void clear() {
            ConcurrentLRUCache.this.clear();
        }

        @Override
        public boolean isEmpty() {
            return ConcurrentLRUCache.this.cache.isEmpty();
        }

        @Override
        public Iterator<Entry<K, V>> iterator() {
            return ConcurrentLRUCache.this.cache.values().iterator();
        }

        @Override
        public Object[] toArray() {
            return ConcurrentLRUCache.this.cache.values().toArray();
        }

        @Override
        public <T> T[] toArray(final T[] ts) {
            return ConcurrentLRUCache.this.cache.values().toArray(ts);
        }

        @Override
        public boolean add(final Entry<K, V> kvEntry) {
            return ConcurrentLRUCache.this.put(kvEntry.getKey(), kvEntry.getValue()) == null;
        }

        @Override
        public boolean remove(final Object o) {
            Map.Entry<K, V> entry = (Map.Entry<K, V>) o;
            return ConcurrentLRUCache.this.remove(entry.getKey()) != null;
        }
    }
}