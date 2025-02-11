package io.github.flameyossnowy.velocis.cache;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

@SuppressWarnings({"unchecked", })
public class ConcurrentLFRUCache<K, V> implements Map<K, V> {
    private final int maxSize;
    private final ConcurrentHashMap<K, Map.Entry<K, V>> cache;
    private final ConcurrentHashMap<K, Integer> frequencyMap;
    private final AtomicReference<Node<K, V>> head;
    private final AtomicReference<Node<K, V>> tail;

    private Values values;
    private EntrySet entrySet;

    public ConcurrentLFRUCache(int maxSize) {
        if (maxSize <= 0) throw new IllegalArgumentException("Cache size must be greater than 0");
        this.maxSize = maxSize;
        this.cache = new ConcurrentHashMap<>();
        this.frequencyMap = new ConcurrentHashMap<>();
        this.head = new AtomicReference<>(null);
        this.tail = new AtomicReference<>(null);
    }

    @Override
    public int size() {
        return cache.size();
    }

    @Override
    public boolean isEmpty() {
        return cache.isEmpty();
    }

    @Override
    public boolean containsKey(final Object o) {
        return cache.containsKey(o);
    }

    @Override
    public boolean containsValue(final Object o) {
        return cache.containsValue(o);
    }

    @Override
    public V get(Object key) {
        Node<K, V> node = (Node<K, V>) cache.get(key);
        if (node == null) return null;
        incrementFrequency(node);
        moveToTail(node);
        return node.value;
    }

    @Override
    public V put(K key, V value) {
        Node<K, V> newNode = new Node<>(key, value);
        Node<K, V> existingNode = (Node<K, V>) cache.putIfAbsent(key, newNode);
        if (existingNode != null) {
            existingNode.value = value;
            incrementFrequency(existingNode);
            moveToTail(existingNode);
            return existingNode.value;
        }
        addNodeToTail(newNode);
        if (cache.size() > maxSize) evictLFRU();
        return null;
    }

    @Override
    public V remove(Object key) {
        Node<K, V> node = (Node<K, V>) cache.remove(key);
        if (node != null) {
            removeNode(node);
            frequencyMap.remove(key);
            return node.value;
        }
        return null;
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
        frequencyMap.clear();
        head.set(null);
        tail.set(null);
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
        return (entrySet == null) ? (entrySet = new EntrySet()) : entrySet;
    }

    private void incrementFrequency(Node<K, V> node) {
        frequencyMap.merge(node.key, 1, Integer::sum);
        node.frequency = frequencyMap.get(node.key);
    }

    private void moveToTail(Node<K, V> node) {
        if (tail.get() == node) return;
        removeNode(node);
        addNodeToTail(node);
    }

    private void addNodeToTail(Node<K, V> node) {
        Node<K, V> oldTail;
        do {
            oldTail = tail.get();
            node.prev = oldTail;
        } while (!tail.compareAndSet(oldTail, node));
        if (oldTail != null) oldTail.next = node;
        else head.set(node);
    }

    private void removeNode(Node<K, V> node) {
        Node<K, V> prev = node.prev;
        Node<K, V> next = node.next;
        if (prev != null) prev.next = next;
        else head.set(next);
        if (next != null) next.prev = prev;
        else tail.set(prev);
    }

    private void evictLFRU() {
        Node<K, V> leastUsed = head.get();
        Node<K, V> current = head.get();
        while (current != null) {
            if (current.frequency < leastUsed.frequency) {
                leastUsed = current;
            }
            current = current.next;
        }
        if (leastUsed != null) remove(leastUsed.key);
    }

    private class Values implements Collection<V> {
        @Override
        public int size() {
            return ConcurrentLFRUCache.this.cache.size();
        }

        @Override
        public boolean contains(Object o) {
            return ConcurrentLFRUCache.this.cache.containsValue(o);
        }

        @Override
        public boolean containsAll(Collection<?> c) {
            return ConcurrentLFRUCache.this.cache.containsValue(c);
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
            return ConcurrentLFRUCache.this.cache.isEmpty();
        }

        @Override
        public Iterator<V> iterator() {
            return new ValuesIterator();
        }

        @Override
        public Object[] toArray() {
            return ConcurrentLFRUCache.this.cache.values().toArray();
        }

        @Override
        public <T> T[] toArray(T[] a) {
            return ConcurrentLFRUCache.this.cache.values().toArray(a);
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
        private final Iterator<Map.Entry<K, V>> iterator = ConcurrentLFRUCache.this.cache.values().iterator();

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
            return ConcurrentLFRUCache.this.cache.size();
        }

        @Override
        public boolean contains(Object o) {
            return ConcurrentLFRUCache.this.cache.containsValue(((Map.Entry<K, V>) o).getValue());
        }

        @Override
        public boolean containsAll(Collection<?> c) {
            return ConcurrentLFRUCache.this.cache.containsValue(c);
        }

        @Override
        public boolean addAll(final Collection<? extends Entry<K, V>> collection) {
            boolean modified = false;
            for (Entry<K, V> entry : collection) {
                modified |= ConcurrentLFRUCache.this.put(entry.getKey(), entry.getValue()) == null;
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
                if (collection.contains(ConcurrentLFRUCache.this.get(entry.getKey()))) {
                    continue;
                }
                modified |= ConcurrentLFRUCache.this.remove(entry.getKey()) != null;
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
                modified |= ConcurrentLFRUCache.this.remove(entry.getKey()) != null;
            }
            return modified;
        }

        @Override
        public void clear() {
            ConcurrentLFRUCache.this.clear();
        }

        @Override
        public boolean isEmpty() {
            return ConcurrentLFRUCache.this.cache.isEmpty();
        }

        @Override
        public Iterator<Entry<K, V>> iterator() {
            return ConcurrentLFRUCache.this.cache.values().iterator();
        }

        @Override
        public Object[] toArray() {
            return ConcurrentLFRUCache.this.cache.values().toArray();
        }

        @Override
        public <T> T[] toArray(final T[] ts) {
            return ConcurrentLFRUCache.this.cache.values().toArray(ts);
        }

        @Override
        public boolean add(final Entry<K, V> kvEntry) {
            return ConcurrentLFRUCache.this.put(kvEntry.getKey(), kvEntry.getValue()) == null;
        }

        @Override
        public boolean remove(final Object o) {
            Map.Entry<K, V> entry = (Map.Entry<K, V>) o;
            return ConcurrentLFRUCache.this.remove(entry.getKey()) != null;
        }
    }
}
