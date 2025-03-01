package io.github.flameyossnowy.velocis.cache.multimap;

import io.github.flameyossnowy.velocis.cache.algorithms.ConcurrentLRUCache;

import java.util.List;

public class ConcurrentLRUMultimap<K, V> extends ConcurrentLRUCache<K, List<V>> implements Multimap<K, V> {
}
