package io.github.flameyossnowy.velocis.cache.algorithms;

import java.util.Map;

class Node<K, V> implements Map.Entry<K, V> {
    int frequency;
    K key;
    V value;
    volatile Node<K, V> prev, next;

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