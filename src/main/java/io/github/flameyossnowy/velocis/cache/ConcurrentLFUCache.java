package io.github.flameyossnowy.velocis.cache;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.PriorityBlockingQueue;

public class ConcurrentLFUCache<K, V> implements Map<K, V> {
    private final int maxSize;
    private final Map<K, V> cache;
    private final Map<K, Integer> frequency;
    private final ConcurrentSkipListSet<K> evictionQueue;

    public ConcurrentLFUCache(int maxSize) {
        if (maxSize <= 0) throw new IllegalArgumentException("Cache size must be greater than 0");
        this.maxSize = maxSize;
        this.cache = new ConcurrentHashMap<>();
        this.frequency = new ConcurrentHashMap<>();
        this.evictionQueue = new ConcurrentSkipListSet<>(Comparator.comparingInt(frequency::get));
    }

    @Override
    public V get(Object key) {
        K castedKey = (K) key;
        V value = cache.get(key);
        if (value == null) return null;

        incrementFrequency(castedKey);
        return value;
    }

    @Override
    public V put(K key, V value) {
        if (value == null) throw new IllegalArgumentException("Null values are not allowed");

        V oldValue = cache.get(key);
        if (oldValue != null) {
            cache.put(key, value);
            incrementFrequency(key);
            return oldValue;
        }

        if (cache.size() >= maxSize) evictLFU();

        cache.put(key, value);
        frequency.put(key, 1);
        evictionQueue.add(key);
        return value;
    }

    @Override
    public void putAll(final Map<? extends K, ? extends V> map) {
        for (Entry<? extends K, ? extends V> entry : map.entrySet()) {
            this.put(entry.getKey(), entry.getValue());
        }
    }

    @Override
    public V remove(Object key) {
        frequency.remove(key);
        evictionQueue.remove(key);
        return cache.remove(key);
    }

    @Override
    public void clear() {
        cache.clear();
        frequency.clear();
        evictionQueue.clear();
    }

    @Override
    public Set<K> keySet() {
        return Set.of();
    }

    @Override
    public Collection<V> values() {
        return List.of();
    }

    @Override
    public Set<Entry<K, V>> entrySet() {
        return Set.of();
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

    private void incrementFrequency(K key) {
        frequency.put(key, frequency.getOrDefault(key, 0) + 1);
        evictionQueue.remove(key);
        evictionQueue.add(key);
    }

    private void evictLFU() {
        if (!evictionQueue.isEmpty()) {
            K leastUsedKey = evictionQueue.first();
            evictionQueue.remove(leastUsedKey);
            if (leastUsedKey != null) {
                cache.remove(leastUsedKey);
                frequency.remove(leastUsedKey);
            }
        }
    }
}
