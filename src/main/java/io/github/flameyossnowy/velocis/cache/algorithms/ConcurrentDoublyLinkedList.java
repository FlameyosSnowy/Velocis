package io.github.flameyossnowy.velocis.cache.algorithms;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.atomic.AtomicReference;

@ApiStatus.Internal
public class ConcurrentDoublyLinkedList<K, V> {
    private final AtomicReference<Node<K, V>> head;
    private final AtomicReference<Node<K, V>> tail;

    public ConcurrentDoublyLinkedList() {
        head = new AtomicReference<>();
        tail = new AtomicReference<>();
    }

    public Node<K, V> addToEnd(K key, V value) {
        Node<K, V> node = new Node<>(key, value);
        insertAtEnd(node);
        return node;
    }

    public Node<K, V> addToEnd(Node<K, V> node) {
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

    private void insertAtEnd(@NotNull Node<K, V> node) {
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

    public AtomicReference<Node<K, V>> getHead() {
        return head;
    }

    public AtomicReference<Node<K, V>> getTail() {
        return tail;
    }
}