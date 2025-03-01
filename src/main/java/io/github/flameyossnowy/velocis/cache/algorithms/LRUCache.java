package io.github.flameyossnowy.velocis.cache.algorithms;

import java.util.LinkedHashMap;

public class LRUCache<K, V> extends LinkedHashMap<K, V> {
    public LRUCache(int maxSize) {
        super(maxSize, 0.75F, true);
    }

    public LRUCache(int maxSize, float loadFactor) {
        super(maxSize, loadFactor, true);
    }

    public LRUCache() {
        this(16);
    }
}
