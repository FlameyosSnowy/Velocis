package io.github.flameyossnowy.velocis.cache.multimap;

import java.util.HashMap;
import java.util.List;

public class NormalMultimap<K, V> extends HashMap<K, List<V>> implements Multimap<K, V> {
    public NormalMultimap(int size) {
        super(size);
    }

    public NormalMultimap() {
        super();
    }

    public NormalMultimap(int size, float loadFactor) {
        super(size, loadFactor);
    }
}
