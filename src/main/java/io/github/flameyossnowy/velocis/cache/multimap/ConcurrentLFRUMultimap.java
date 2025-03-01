package io.github.flameyossnowy.velocis.cache.multimap;

import io.github.flameyossnowy.velocis.cache.algorithms.ConcurrentLFRUCache;

import java.util.List;

public class ConcurrentLFRUMultimap<K, V> extends ConcurrentLFRUCache<K, List<V>> implements Multimap<K, V> {
    public ConcurrentLFRUMultimap(int maxSize, int concurrencyLevel, float loadFactor) {
        super(maxSize, concurrencyLevel, loadFactor);
    }

    public ConcurrentLFRUMultimap(int maxSize, int concurrencyLevel) {
        super(maxSize, concurrencyLevel);
    }

    public ConcurrentLFRUMultimap(int maxSize, float loadFactor) {
        super(maxSize, loadFactor);
    }

    public ConcurrentLFRUMultimap(int maxSize) {
        super(maxSize);
    }

    public ConcurrentLFRUMultimap() {
        super();
    }
}
