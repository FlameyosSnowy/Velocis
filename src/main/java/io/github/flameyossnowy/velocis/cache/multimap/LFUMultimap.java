package io.github.flameyossnowy.velocis.cache.multimap;

import io.github.flameyossnowy.velocis.cache.algorithms.LFUCache;

import java.util.List;

public class LFUMultimap<K, V> extends LFUCache<K, List<V>> implements Multimap<K, V> {
}
