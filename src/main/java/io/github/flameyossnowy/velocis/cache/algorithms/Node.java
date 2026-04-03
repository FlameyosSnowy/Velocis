package io.github.flameyossnowy.velocis.cache.algorithms;

import org.jetbrains.annotations.ApiStatus;

import java.util.Map;

@ApiStatus.Internal
public class Node<K, V> implements Map.Entry<K, V> {
    public K key;
    public V value;
    volatile Node<K, V> prev;
    public volatile Node<K, V> next;

    public Node(K key, V value) {
        this.key = key;
        this.value = value;
    }

    @Override
    public K getKey() {
        return key;
    }

    @Override
    public V getValue() {
        return value;
    }

    @Override
    public V setValue(final V v) {
        V oldValue = this.value;
        this.value = v;
        return oldValue;
    }
}