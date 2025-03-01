package io.github.flameyossnowy.velocis.cache.multimap;

import io.github.flameyossnowy.velocis.cache.algorithms.ConcurrentLFRUCache;

import java.util.List;

public class ConcurrentLFRUMultimap<K, V> extends ConcurrentLFRUCache<K, List<V>> implements Multimap<K, V> {
}
