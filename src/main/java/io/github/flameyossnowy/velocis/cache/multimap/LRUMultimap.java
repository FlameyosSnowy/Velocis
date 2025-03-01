package io.github.flameyossnowy.velocis.cache.multimap;

import io.github.flameyossnowy.velocis.cache.algorithms.LRUCache;

import java.util.List;

public class LRUMultimap<K, V> extends LRUCache<K, List<V>> implements Multimap<K, V> {
}
