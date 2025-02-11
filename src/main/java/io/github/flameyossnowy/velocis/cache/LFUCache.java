package io.github.flameyossnowy.velocis.cache;

import java.util.*;

/**
 * LFU Cache implementation of the Cache interface.
 * Uses HashMap for storage, HashMap for frequency tracking, and a Min-Heap for eviction.
 */
public class LFUCache<K, V> implements Map<K, V> {
    private final int maxSize;
    private final Map<K, V> cache;
    private final Map<K, Integer> frequency;
    private final PriorityQueue<K> evictionQueue;

    public LFUCache(int maxSize) {
        if (maxSize <= 0) throw new IllegalArgumentException("Cache size must be greater than 0");
        this.maxSize = maxSize;
        this.cache = new HashMap<>();
        this.frequency = new HashMap<>();
        this.evictionQueue = new PriorityQueue<>(Comparator.comparingInt(frequency::get));
    }

    @Override
    public V get(Object key) {
        V value = cache.get(key);
        if (value == null) return null;

        incrementFrequency((K) key);
        return value;
    }

    @Override
    public V put(K key, V value) {
        if (value == null) throw new IllegalArgumentException("Null values are not allowed");

        if (cache.containsKey(key)) {
            incrementFrequency(key);
            return cache.put(key, value);
        }

        if (cache.size() >= maxSize) evictLFU();

        frequency.put(key, 1);
        evictionQueue.offer(key);
        return cache.put(key, value);
    }

    @Override
    public V remove(Object key) {
        frequency.remove(key);
        evictionQueue.remove(key);
        return cache.remove(key);
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
        frequency.clear();
        evictionQueue.clear();
    }

    @Override
    public Set<K> keySet() {
        return cache.keySet();
    }

    @Override
    public Collection<V> values() {
        return cache.values();
    }

    @Override
    public Set<Entry<K, V>> entrySet() {
        return cache.entrySet();
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

    private void incrementFrequency(K key) {
        frequency.put(key, frequency.getOrDefault(key, 0) + 1);
        evictionQueue.remove(key);
        evictionQueue.offer(key);
    }

    private void evictLFU() {
        if (!evictionQueue.isEmpty()) {
            K leastUsedKey = evictionQueue.poll();
            if (leastUsedKey != null) {
                cache.remove(leastUsedKey);
                frequency.remove(leastUsedKey);
            }
        }
    }
}