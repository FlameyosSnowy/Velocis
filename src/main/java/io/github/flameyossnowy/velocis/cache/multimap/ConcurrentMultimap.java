package io.github.flameyossnowy.velocis.cache.multimap;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class ConcurrentMultimap<K, V> extends ConcurrentHashMap<K, List<V>> implements Multimap<K, V> {
    public ConcurrentMultimap() {
        super();
    }

    public ConcurrentMultimap(int size) {
        super(size);
    }

    public ConcurrentMultimap(int size, float loadFactor) {
        super(size, loadFactor);
    }

    public ConcurrentMultimap(int size, float loadFactor, int concurrencyLevel) {
        super(size, loadFactor, concurrencyLevel);
    }
}