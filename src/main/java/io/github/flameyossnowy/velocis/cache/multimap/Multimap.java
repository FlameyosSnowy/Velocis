package io.github.flameyossnowy.velocis.cache.multimap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public interface Multimap<K, V> extends Map<K, List<V>> {
    default List<V> putElement(K key, V value) {
        List<V> values = get(key);
        if (values == null) {
            values = new ArrayList<>();
            this.put(key, values);
        }
        values.add(value);
        return values;
    }

    default List<V> removeElement(K key, V value) {
        List<V> values = get(key);
        if (values == null) return List.of();

        if (values.size() == 1) {
            // Optimize to clear instead of make a new array
            values.clear();
            this.remove(key);
            return values;
        }
        values.remove(value);
        return values;
    }

    default V getFirstElement(K key) {
        List<V> values = get(key);
        if (values == null) return null;
        return values.get(0);
    }

    default V getLastElement(K key) {
        List<V> values = get(key);
        if (values == null) return null;
        return values.get(values.size() - 1);
    }
}
