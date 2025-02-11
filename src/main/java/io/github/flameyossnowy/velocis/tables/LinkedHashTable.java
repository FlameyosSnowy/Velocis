package io.github.flameyossnowy.velocis.tables;

import java.util.*;

@SuppressWarnings({"unused"})
public class LinkedHashTable<R, C, V> extends HashTable<R, C, V> {

    private LinkedNode<R, C, V> head;
    private LinkedNode<R, C, V> tail;

    public LinkedHashTable() {
        super();
    }

    public LinkedHashTable(int size) {
        super(size);
    }

    public LinkedHashTable(int size, float loadFactor) {
        super(size, loadFactor);
    }

    @Override
    public V put(R row, C column, V value) {
        V oldValue = super.put(row, column, value);

        if (oldValue == null) {
            addToLinkedList(row, column, value);
        } else {
            updateLinkedList(row, column, value);
        }

        return oldValue;
    }

    @Override
    public V remove(R row, C column) {
        V oldValue = super.remove(row, column);

        if (oldValue != null) {
            removeFromLinkedList(row, column);
        }

        return oldValue;
    }

    @Override
    public void clear() {
        super.clear();
        head = null;
        tail = null;
    }

    private void addToLinkedList(R row, C column, V value) {
        LinkedNode<R, C, V> newNode = new LinkedNode<>(row, column, value);

        if (tail == null) {
            // First node
            head = tail = newNode;
        } else {
            // Add to the end of the list
            tail.next = newNode;
            newNode.prev = tail;
            tail = newNode;
        }
    }

    private void updateLinkedList(R row, C column, V value) {
        LinkedNode<R, C, V> current = head;

        while (current != null) {
            if (Objects.equals(current.row, row) && Objects.equals(current.column, column)) {
                current.value = value;
                return;
            }
            current = current.next;
        }
    }

    private void removeFromLinkedList(R row, C column) {
        LinkedNode<R, C, V> current = head;

        while (current != null) {
            if (Objects.equals(current.row, row) && Objects.equals(current.column, column)) {
                if (current.prev == null) {
                    // Removing the head
                    head = current.next;
                } else {
                    current.prev.next = current.next;
                }

                if (current.next == null) {
                    // Removing the tail
                    tail = current.prev;
                } else {
                    current.next.prev = current.prev;
                }

                return;
            }
            current = current.next;
        }
    }

    @Override
    public Set<Entry<R, C, V>> entrySet() {
        return new LinkedEntrySet();
    }

    @Override
    public Map<C, V> row(R row) {
        return new LinkedRowMap(row);
    }

    public class LinkedRowMap extends RowMap {
        public LinkedRowMap(R row) {
            super(row);
        }

        @Override
        public Set<Entry<C, V>> entrySet() {
            return new LinkedRowEntrySet(row);
        }

        @Override
        public Set<C> keySet() {
            return new LinkedRowKeySet(row);
        }

        @Override
        public Collection<V> values() {
            return new LinkedRowValueSet(row);
        }
    }

    public class LinkedRowEntrySet extends RowMapEntries {
        public LinkedRowEntrySet(final R row) {
            super(row);
        }

        @Override
        public Iterator<Map.Entry<C, V>> iterator() {
            return new LinkedRowEntryIterator(row);
        }
    }

    public class LinkedRowEntryIterator implements Iterator<Map.Entry<C, V>> {
        private LinkedNode<R, C, V> current = head; // Start from the head of the linked list
        private final int expectedModCount = modCount; // Track modifications to detect concurrent modifications
        private final R row; // The specific row we want to iterate over

        public LinkedRowEntryIterator(final R row) {
            this.row = row;
            // Advance to the first node belonging to the specified row
            advanceToRow();
        }

        @Override
        public boolean hasNext() {
            // Ensure the current node is not null and belongs to the specified row
            return current != null && current.row.equals(row);
        }

        @Override
        public Map.Entry<C, V> next() {
            if (expectedModCount != modCount) {
                throw new ConcurrentModificationException();
            }
            if (!hasNext()) {
                throw new NoSuchElementException();
            }

            // Store the current node to return as the next entry
            LinkedNode<R, C, V> node = current;

            // Advance to the next node belonging to the specified row
            current = current.next;
            advanceToRow();

            return Map.entry(node.column, node.value);
        }

        /**
         * Advances the `current` pointer to the next node belonging to the specified row.
         */
        private void advanceToRow() {
            while (current != null && !current.row.equals(row)) {
                current = current.next;
            }
        }
    }

    public class LinkedRowKeySet extends RowMapKeySet {
        public LinkedRowKeySet(final R row) {
            super(row);
        }

        @Override
        public Iterator<C> iterator() {
            return new LinkedRowKeyIterator();
        }
    }

    public class LinkedRowKeyIterator implements Iterator<C> {
        private LinkedNode<R, C, V> current = head;

        @Override
        public boolean hasNext() {
            return current != null;
        }

        @Override
        public C next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }

            LinkedNode<R, C, V> node = current;
            current = current.next;
            return node.column;
        }
    }

    public class LinkedRowValueSet extends RowMapValues {
        public LinkedRowValueSet(final R row) {
            super(row);
        }

        @Override
        public Iterator<V> iterator() {
            return new LinkedRowValueIterator();
        }
    }

    public class LinkedRowValueIterator implements Iterator<V> {
        private LinkedNode<R, C, V> current = head;

        @Override
        public boolean hasNext() {
            return current != null;
        }

        @Override
        public V next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }

            LinkedNode<R, C, V> node = current;
            current = current.next;
            return node.value;
        }
    }



    private class LinkedEntrySet extends EntrySet {

        @Override
        public Iterator<Entry<R, C, V>> iterator() {
            return new LinkedEntryIterator();
        }
    }

    private class LinkedEntryIterator implements Iterator<Entry<R, C, V>> {

        private LinkedNode<R, C, V> current = head;

        @Override
        public boolean hasNext() {
            return current != null;
        }

        @Override
        public Table.Entry<R, C, V> next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }

            LinkedNode<R, C, V> node = current;
            current = current.next;
            return node;
        }
    }

    private static class LinkedNode<R, C, V> extends Node<R, C, V> {
        LinkedNode<R, C, V> prev;
        LinkedNode<R, C, V> next;

        public LinkedNode(R row, C column, V value) {
            super(row, column, value);
        }
    }
}