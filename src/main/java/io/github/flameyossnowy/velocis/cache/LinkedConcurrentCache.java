package io.github.flameyossnowy.velocis.cache;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@SuppressWarnings("unused")
public class LinkedConcurrentCache<K, V> extends AbstractMap<K, V> implements Map<K, V> {
    private static class Node<K, V> implements Entry<K, V> {
        final K key;
        V value;
        volatile Node<K, V> prev;
        volatile Node<K, V> next;

        Node(K key, V value) {
            this.key = key;
            this.value = value;
        }

        @Override
        public K getKey() {
            return key;
        }

        @Override
        public V getValue() {
            return value;
        }

        @Override
        public V setValue(final V v) {
            V oldValue = this.value;
            this.value = v;
            return oldValue;
        }
    }

    private final ConcurrentHashMap<K, Node<K, V>> map;
    private final AtomicReference<Node<K, V>> head;
    private final AtomicReference<Node<K, V>> tail;

    private Values values;
    private EntrySet entrySet;
    private KeySet keySet;

    private static final int INITIAL_CAPACITY = 16;
    private static final int INITIAL_CONCURRENCY_LEVEL = 1;
    private static final float INITIAL_LOAD_FACTOR =  0.75F;

    public LinkedConcurrentCache(int preallocatedSize, float loadFactor, int concurrencyLevel) {
        this.map = new ConcurrentHashMap<>(preallocatedSize, loadFactor, concurrencyLevel);
        this.head = new AtomicReference<>();
        this.tail = new AtomicReference<>();
    }

    public LinkedConcurrentCache(int preallocatedSize, int loadFactor) {
        this(preallocatedSize, loadFactor, INITIAL_CONCURRENCY_LEVEL);
    }

    public LinkedConcurrentCache(int preallocatedSize) {
        this(preallocatedSize, INITIAL_LOAD_FACTOR, INITIAL_CONCURRENCY_LEVEL);
    }

    public LinkedConcurrentCache() {
        this(INITIAL_CAPACITY);
    }

    public LinkedConcurrentCache(Map<? extends K, ? extends V> map) {
        this(INITIAL_CAPACITY);
        putAll(map);
    }

    @Override
    public Set<K> keySet() {
        return keySet == null ? (keySet = new KeySet()) : keySet;
    }

    @Override
    public Collection<V> values() {
        return values == null ? (values = new Values()) : values;
    }

    @Override
    public Set<Entry<K, V>> entrySet() {
        return entrySet == null ? (entrySet = new EntrySet()) : entrySet;
    }

    @Override
    public int size() {
        return map.size();
    }

    @Override
    public boolean isEmpty() {
        return map.isEmpty();
    }

    @Override
    public boolean containsKey(final Object o) {
        return map.containsKey(o);
    }

    @Override
    public boolean containsValue(final Object o) {
        for (var entry : this.values()) {
            if (entry.equals(o)) return true;
        }
        return false;
    }

    public V get(Object key) {
        return map.get(key).value;
    }

    public V put(K key, V value) {
        Node<K, V> newNode = new Node<>(key, value);
        while (true) {
            Node<K, V> oldNode = map.putIfAbsent(key, newNode);
            if (oldNode != null) {
                oldNode.value = value;
                moveToTail(oldNode);
                return value;
            }
            addNodeToTail(newNode);
        }
    }

    public V remove(Object key) {
        Node<K, V> node = map.remove(key);
        if (node != null) {
            removeNode(node);
            return node.value;
        }
        return null;
    }

    @Override
    public void putAll(final Map<? extends K, ? extends V> map) {
        for (var entry : map.entrySet()) {
            this.put(entry.getKey(), entry.getValue());
        }
    }

    public void clear() {
        map.clear();
        head.set(null);
        tail.set(null);
    }

    private void addNodeToTail(Node<K, V> node) {
        Node<K, V> oldTail;
        do {
            oldTail = tail.get();
            node.prev = oldTail;
        } while (!tail.compareAndSet(oldTail, node));

        if (oldTail != null) {
            oldTail.next = node;
        } else {
            head.set(node);
        }
    }

    private void removeNode(Node<K, V> node) {
        Node<K, V> prev = node.prev;
        Node<K, V> next = node.next;

        if (prev != null) {
            prev.next = next;
        } else {
            head.set(next);
        }

        if (next != null) {
            next.prev = prev;
        } else {
            tail.set(prev);
        }
    }

    private void moveToTail(Node<K, V> node) {
        if (tail.get() == node) {
            return;
        }
        removeNode(node);
        addNodeToTail(node);
    }

    private class KeySet extends AbstractSet<K> {
        @Override
        public Iterator<K> iterator() {
            return new KeyIterator();
        }

        @Override
        public int size() {
            return LinkedConcurrentCache.this.size();
        }

        @Override
        public boolean contains(Object o) {
            return LinkedConcurrentCache.this.containsKey(o);
        }

        @Override
        public boolean remove(Object o) {
            return LinkedConcurrentCache.this.remove(o) != null;
        }

        @Override
        public void clear() {
            LinkedConcurrentCache.this.clear();
        }

        private class KeyIterator implements Iterator<K> {
            private Node<K, V> current = head.get();
            private Node<K, V> lastReturned;

            @Override
            public boolean hasNext() {
                return current != null;
            }

            @Override
            public K next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                lastReturned = current;
                current = current.next;
                return lastReturned.key;
            }

            @Override
            public void remove() {
                if (lastReturned == null) {
                    throw new IllegalStateException();
                }
                LinkedConcurrentCache.this.remove(lastReturned.key);
                lastReturned = null;
            }
        }
    }

    private class Values extends AbstractCollection<V> {
        @Override
        public Iterator<V> iterator() {
            return new ValueIterator();
        }

        @Override
        public int size() {
            return LinkedConcurrentCache.this.size();
        }

        @Override
        public boolean contains(Object o) {
            return LinkedConcurrentCache.this.containsValue(o);
        }

        @Override
        public void clear() {
            LinkedConcurrentCache.this.clear();
        }

        private class ValueIterator implements Iterator<V> {
            private Node<K, V> current = head.get();
            private Node<K, V> lastReturned;

            @Override
            public boolean hasNext() {
                return current != null;
            }

            @Override
            public V next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                lastReturned = current;
                current = current.next;
                return lastReturned.value;
            }

            @Override
            public void remove() {
                if (lastReturned == null) {
                    throw new IllegalStateException();
                }
                LinkedConcurrentCache.this.remove(lastReturned.key);
                lastReturned = null;
            }
        }
    }

    private class EntrySet extends AbstractSet<Entry<K, V>> {
        @Override
        public Iterator<Entry<K, V>> iterator() {
            return new EntryIterator();
        }

        @Override
        public int size() {
            return LinkedConcurrentCache.this.size();
        }

        @Override
        public boolean contains(Object o) {
            if (!(o instanceof final Map.Entry<?, ?> entry)) return false;
            V value = LinkedConcurrentCache.this.get(entry.getKey());
            return value != null && value.equals(entry.getValue());
        }

        @Override
        public boolean remove(Object o) {
            if (!(o instanceof final Map.Entry<?, ?> entry)) return false;
            return LinkedConcurrentCache.this.remove(entry.getKey()) != null;
        }

        @Override
        public boolean add(Entry<K, V> o) {
            return LinkedConcurrentCache.this.put(o.getKey(), o.getValue()) != null;
        }

        @Override
        public void clear() {
            LinkedConcurrentCache.this.clear();
        }

        private class EntryIterator implements Iterator<Entry<K, V>> {
            private Node<K, V> current = head.get();
            private Node<K, V> lastReturned;

            @Override
            public boolean hasNext() {
                return current != null;
            }

            @Override
            public Entry<K, V> next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                lastReturned = current;
                current = current.next;
                return lastReturned;
            }

            @Override
            public void remove() {
                if (lastReturned == null) {
                    throw new IllegalStateException();
                }
                
                LinkedConcurrentCache.this.remove(lastReturned.key);
                lastReturned = null;
            }
        }
    }
}
