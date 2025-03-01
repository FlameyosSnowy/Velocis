package io.github.flameyossnowy.velocis.cache.multimap;

import io.github.flameyossnowy.velocis.cache.algorithms.ConcurrentLFUCache;

import java.util.List;

public class ConcurrentLFUMultimap<K, V> extends ConcurrentLFUCache<K, List<V>> implements Multimap<K, V> {
}
