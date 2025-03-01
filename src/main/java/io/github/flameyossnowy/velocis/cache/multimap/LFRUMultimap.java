package io.github.flameyossnowy.velocis.cache.multimap;

import io.github.flameyossnowy.velocis.cache.algorithms.LFRUCache;

import java.util.List;

public class LFRUMultimap<K, V> extends LFRUCache<K, List<V>> implements Multimap<K, V> {
}
