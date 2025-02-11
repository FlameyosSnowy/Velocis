package io.github.flameyossnowy.velocis.cache;

import java.util.concurrent.atomic.AtomicReference;

class ConcurrentDoublyLinkedList<K, V> {
    private final AtomicReference<Node<K, V>> head;
    private final AtomicReference<Node<K, V>> tail;

    public ConcurrentDoublyLinkedList() {
        head = new AtomicReference<>(null);
        tail = new AtomicReference<>(null);
    }

    public Node<K, V> addToEnd(K key, V value) {
        Node<K, V> node = new Node<>(key, value);
        insertAtEnd(node);
        return node;
    }

    public void moveToEnd(Node<K, V> node) {
        remove(node);
        insertAtEnd(node);
    }

    public Node<K, V> removeHead() {
        if (head.get().next == tail.get()) return null; // List is empty

        Node<K, V> lruNode = head.get().next;
        remove(lruNode);
        return lruNode;
    }

    public void clear() {
        head.set(tail.get());
        tail.set(head.get());
    }

    private void insertAtEnd(Node<K, V> node) {
        Node<K, V> oldTail;
        do {
            oldTail = tail.get();
            node.prev = oldTail;
        } while (!tail.compareAndSet(oldTail, node));

        if (oldTail != null) {
            oldTail.next = node;
        } else {
            head.set(node);
        }
    }

    public void remove(Node<K, V> node) {
        if (node == null) return;
        node.prev.next = node.next;
        node.next.prev = node.prev;
    }
}