package io.github.flameyossnowy.velocis.cache.multimap;

import io.github.flameyossnowy.velocis.cache.algorithms.ConcurrentLRUCache;

import java.util.List;

public class ConcurrentLRUMultimap<K, V> extends ConcurrentLRUCache<K, List<V>> implements Multimap<K, V> {
    public ConcurrentLRUMultimap(int maxSize, int concurrencyLevel, float loadFactor) {
        super(maxSize, concurrencyLevel, loadFactor);
    }

    public ConcurrentLRUMultimap(int maxSize, int concurrencyLevel) {
        super(maxSize, concurrencyLevel);
    }

    public ConcurrentLRUMultimap(int maxSize, float loadFactor) {
        super(maxSize, loadFactor);
    }

    public ConcurrentLRUMultimap(int maxSize) {
        super(maxSize);
    }

    public ConcurrentLRUMultimap() {
        super();
    }
}
