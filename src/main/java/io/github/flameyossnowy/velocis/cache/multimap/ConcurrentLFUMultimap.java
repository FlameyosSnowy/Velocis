package io.github.flameyossnowy.velocis.cache.multimap;

import io.github.flameyossnowy.velocis.cache.algorithms.ConcurrentLFUCache;

import java.util.List;

public class ConcurrentLFUMultimap<K, V> extends ConcurrentLFUCache<K, List<V>> implements Multimap<K, V> {
    public ConcurrentLFUMultimap(int maxSize, int concurrencyLevel, float loadFactor) {
        super(maxSize, concurrencyLevel, loadFactor);
    }

    public ConcurrentLFUMultimap(int maxSize, int concurrencyLevel) {
        super(maxSize, concurrencyLevel);
    }

    public ConcurrentLFUMultimap(int maxSize, float loadFactor) {
        super(maxSize, loadFactor);
    }

    public ConcurrentLFUMultimap(int maxSize) {
        super(maxSize);
    }

    public ConcurrentLFUMultimap() {
        super();
    }
}
