package io.github.flameyossnowy.velocis.cache.algorithms;

import org.jetbrains.annotations.ApiStatus;

import java.util.concurrent.atomic.AtomicMarkableReference;

/**
 * Lock-free concurrent doubly-linked list using the Sundell & Tsigas algorithm.
 *
 * Deletion is two-phase:
 *   1. Logical deletion  — mark node.next with the deletion bit via CAS.
 *   2. Physical deletion — swing predecessor's next pointer past the node.
 *
 * Any thread that encounters a marked node helps complete the physical unlink
 * before proceeding, which keeps the list clean without a dedicated GC thread.
 *
 * Sentinel head and tail nodes are allocated once and never removed.
 */
@ApiStatus.Internal
public class ConcurrentDoublyLinkedList<K, V> {

    public static final class Node<K, V> {
        final K key;
        volatile V value;

        /**
         * next: the mark bit means this node is logically deleted.
         * Once marked, no thread will insert after it or move it.
         */
        final AtomicMarkableReference<Node<K, V>> next;

        /**
         * prev is a best-effort back-pointer used to speed up backwards
         * traversal during moveToEnd.  It may transiently point to a deleted
         * node; callers must follow the chain until they reach a live node.
         */
        volatile Node<K, V> prev;

        // sentinel constructor
        Node() {
            this.key   = null;
            this.value = null;
            this.next  = new AtomicMarkableReference<>(null, false);
            this.prev  = null;
        }

        Node(K key, V value) {
            this.key   = key;
            this.value = value;
            this.next  = new AtomicMarkableReference<>(null, false);
            this.prev  = null;
        }

        /** Returns true if this node has been logically deleted. */
        boolean isDeleted() {
            return next.isMarked();
        }
    }

    private final Node<K, V> head;
    private final Node<K, V> tail;

    public ConcurrentDoublyLinkedList() {
        head = new Node<>();
        tail = new Node<>();
        head.next.set(tail, false);
        tail.prev = head;
    }

    /**
     * Appends a new node at the tail (most-recently-used end) and returns it.
     * Retries with CAS until the insertion succeeds.
     */
    public Node<K, V> addToEnd(K key, V value) {
        Node<K, V> node = new Node<>(key, value);
        insertBefore(tail, node);
        return node;
    }

    /**
     * Logically deletes {@code node} and then moves it to the tail.
     * Used by LRU get/put to mark a node as most-recently-used.
     */
    public void moveToEnd(Node<K, V> node) {
        if (!physicallyUnlink(node)) return; // someone else removed it concurrently
        node.next.set(null, false);          // reset for re-insertion
        insertBefore(tail, node);
    }

    /**
     * Removes and returns the head's successor (the LRU / oldest node),
     * or null if the list is empty.
     */
    public Node<K, V> removeHead() {
        Node<K, V> first = head.next.getReference();
        if (first == tail) return null;
        remove(first);
        return first;
    }

    /**
     * Logically deletes {@code node} (marks its next pointer).
     * Physical unlinking happens lazily during subsequent traversals.
     */
    public void remove(Node<K, V> node) {
        if (node == null || node == head || node == tail) return;
        boolean[] marked = {false};
        Node<K, V> succ;
        do {
            succ = node.next.get(marked);
            if (marked[0]) return; // already deleted by another thread
        } while (!node.next.compareAndSet(succ, succ, false, true));

        helpDelete(node, succ);
    }

    /** Resets to empty (only safe when no concurrent operations are running). */
    public void clear() {
        head.next.set(tail, false);
        tail.prev = head;
    }

    public Node<K, V> getHead() { return head; }
    public Node<K, V> getTail() { return tail; }

    /**
     * Inserts {@code node} immediately before {@code successor}.
     * Retries if {@code successor} is deleted or if CAS fails.
     */
    private void insertBefore(Node<K, V> successor, Node<K, V> node) {
        for (;;) {
            // Find a live predecessor of successor.
            Node<K, V> pred = findLivePred(successor);

            node.prev = pred;
            node.next.set(successor, false);

            if (pred.next.compareAndSet(successor, node, false, false)) {
                successor.prev = node;
                return;
            }
        }
    }

    /**
     * Walks backwards from {@code node} via prev pointers, skipping any
     * logically deleted nodes, until a live predecessor is found.
     */
    private Node<K, V> findLivePred(Node<K, V> node) {
        Node<K, V> pred = node.prev;
        while (pred != null && pred.isDeleted()) {
            pred = pred.prev;
        }
        return (pred == null) ? head : pred;
    }

    /**
     * Physically unlinks {@code node} (which must already be logically deleted)
     * by swinging its predecessor's next pointer to {@code node}'s successor.
     * Returns true if this thread performed the unlink.
     */
    private boolean helpDelete(Node<K, V> node, Node<K, V> succ) {
        boolean[] succMarked = {false};
        while (succ != tail && succ.next.get(succMarked) != null && succMarked[0]) {
            succ = succ.next.getReference();
        }

        Node<K, V> pred = findLivePred(node);
        // Swing pred.next past node to succ.
        return pred.next.compareAndSet(node, succ, false, false);
    }

    /**
     * Unlinks a live (non-deleted) node without marking it.
     * Used by moveToEnd so the node can be re-inserted afterwards.
     * Returns true if this thread successfully unlinked it.
     */
    private boolean physicallyUnlink(Node<K, V> node) {
        boolean[] marked = {false};
        Node<K, V> succ = node.next.get(marked);
        if (marked[0]) return false; // already logically deleted, hands off

        Node<K, V> pred = findLivePred(node);
        return pred.next.compareAndSet(node, succ, false, false);
    }
}