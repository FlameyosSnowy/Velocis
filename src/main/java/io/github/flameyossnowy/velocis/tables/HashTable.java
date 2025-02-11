package io.github.flameyossnowy.velocis.tables;

import java.lang.reflect.Array;
import java.util.*;

/**
 * A hash table implementation of the Table interface.
 * <p>
 * The table uses a hash table to store the entries.
 * @author FlameyosFlow
 * @param <R> The row, or known as the first key.
 * @param <C> The column, or known as the second key.
 * @param <V> The value gotten by the row and the column, or the two keys.
 */
@SuppressWarnings({ "unchecked", "unused" })
public class HashTable<R, C, V> implements Table<R, C, V> {
    int modCount = 0;
    private int size = 0;

    private final float loadFactor;

    private EntrySet entrySet;
    private KeySet keySet;
    private Values values;

    private Table.Entry<R, C, V>[] table;

    private static final int INITIAL_CAPACITY = 16;
    private static final float LOAD_FACTOR = 0.75f;

    /**
     * Constructs a hash table with default capacity and load factor.
     */
    public HashTable() {
        this(INITIAL_CAPACITY, LOAD_FACTOR);
    }

    /**
     * Constructs a hash table with the specified capacity and load factor.
     * @param size The initial capacity of the hash table.
     */
    public HashTable(final int size) {
        this(size, LOAD_FACTOR);
    }

    /**
     * Constructs a hash table with the specified capacity and load factor.
     * @param size The initial capacity of the hash table.
     * @param loadFactor The load factor of the hash table.
     */
    public HashTable(final int size, final float loadFactor) {
        this.loadFactor = loadFactor;
        table = new Node[size];
    }

    // ---------------
    // UTILITY METHODS
    // ---------------

    private int hash(final R row, final C column) {
        int rowCode = row.hashCode();
        int columnCode = column.hashCode();
        int length = table.length;
        return Math.abs((rowCode ^ columnCode) % length);
    }

    private void resize() {
        // Create a new table with double the capacity
        int newCapacity = table.length * 2;

        // Create the new table with the updated capacity
        Table.Entry<R, C, V>[] newTable = new Table.Entry[newCapacity];
        Table.Entry<R, C, V>[] oldTable = this.table; // Reference to the old table
        this.table = newTable; // Update the table reference to the new one

        // Rehash all existing entries into the new table
        for (final Entry<R, C, V> rcvEntry : oldTable) {
            Table.Entry<R, C, V> current = rcvEntry;
            while (current != null) {
                // Recalculate the hash using the new table capacity
                int newHash = hash(current.row(), current.column());

                // Handle collisions: if the bucket is already occupied, chain the new node
                if (newTable[newHash] == null) {
                    // No collision, simply place the node
                    newTable[newHash] = new Node<>(current.row(), current.column(), current.value());
                } else {
                    // Collision handling: traverse to the end of the chain and append the new node
                    Table.Entry<R, C, V> node = newTable[newHash];
                    while (node.next() != null) {
                        node = node.next();
                    }
                    node.setNext(new Node<>(current.row(), current.column(), current.value()));
                }

                // Move to the next node in the current chain in the old table
                current = current.next();
            }
        }
    }


    private V putValue(Table.Entry<R, C, V> node, boolean ifAbsent) {
        if (size >= table.length * loadFactor) {
            resize();
        }

        int hash = this.hash(node.row(), node.column());

        Table.Entry<R, C, V> current = table[hash];
        Table.Entry<R, C, V> previous = null;

        while (current != null) {
            if (Objects.equals(current.row(), node.row()) && Objects.equals(current.column(), node.column())) {
                if (ifAbsent) return current.value();
                current.setValue(node.value());
                return node.value();
            }
            previous = current;
            current = current.next();
        }

        // No existing entry found, append the new node
        Node<R, C, V> newNode = new Node<>(node.row(), node.column(), node.value());
        if (previous == null) {
            // Insert at the head
            table[hash] = newNode;
        } else {
            // Link to the previous node
            previous.setNext(newNode);
        }

        size++;
        modCount++;
        return null;
    }

    // ---------------
    // INSTANCE METHODS
    // ---------------

    @Override
    public boolean containsKey(final R row, final C column) {
        return get(row, column) != null;
    }

    @Override
    public boolean containsValue(final V value) {
        for (Table.Entry<R, C, V> node : table) {
            if (node != null && Objects.equals(node.value(), value)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean contains(final R row, final C column, final V value) {
        if (row == null || column == null) {
            return false;
        }
        V v = get(row, column);
        return v != null && Objects.equals(v, value);
    }

    @Override
    public V getOrDefault(final R row, final C column, final V defaultValue) {
        if (row == null || column == null) {
            return defaultValue;
        }
        V value = get(row, column);
        return value != null ? value : defaultValue;
    }

    @Override
    public V remove(final R row, final C column, final V value) {
        V v = get(row, column);
        if (v != null && Objects.equals(v, value)) {
            remove(row, column);
        }
        return v;
    }

    @Override
    public void forEach(final Table.ForEach<? super R, ? super C, ? super V> action) {
        for (Table.Entry<R, C, V> node : table) {
            action.accept(node.row(), node.column(), node.value());
        }
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public void clear() {
        Arrays.fill(table, null);
        size = 0;
        modCount++;
    }

    @Override
    public boolean isEmpty() {
        return size == 0;
    }

    @Override
    public V get(final R row, final C column) {
        int hash = hash(row, column);
        Table.Entry<R, C, V> current = table[hash];

        while (current != null) {
            if (Objects.equals(current.row(), row) && Objects.equals(current.column(), column)) {
                return current.value();
            }
            current = current.next();
        }
        return null;
    }

    @Override
    public V put(final R row, final C column, final V value) {
        return putValue(new Node<>(row, column, value), false);
    }

    @Override
    public V putIfAbsent(final R row, final C column, final V value) {
        return putValue(new Node<>(row, column, value), true);
    }

    @Override
    public V remove(final R row, final C column) {
        int hash = hash(row, column);
        Table.Entry<R, C, V> current = table[hash];
        Table.Entry<R, C, V> prev = null;

        while (current != null) {
            if (Objects.equals(current.row(), row) && Objects.equals(current.column(), column)) {
                if (prev == null) {
                    table[hash] = current.next();
                } else {
                    prev.setNext(current.next());
                }
                size--;
                modCount++;
                return current.value();
            }
            prev = current;
            current = current.next();
        }
        return null;
    }

    @Override
    public Set<Entry<R, C, V>> entrySet() {
        if (entrySet == null) {
            entrySet = new EntrySet();
        }
        return entrySet;
    }

    @Override
    public Set<KeyEntry<R, C>> keySet() {
        if (keySet == null) {
            keySet = new KeySet();
        }
        return keySet;
    }

    @Override
    public Collection<V> values() {
        if (values == null) {
            values = new Values();
        }
        return values;
    }

    @Override
    public void putAll(final Table<R, C, V> table) {
        for (var entry : table.entrySet()) {
            putValue(entry, false);
        }
    }

    public Map<C, V> row(final R row) {
        return new RowMap(row);
    }

    public class KeySet implements Set<KeyEntry<R, C>> {
        @Override
        public int size() {
            return HashTable.this.size();
        }

        @Override
        public boolean isEmpty() {
            return HashTable.this.isEmpty();
        }

        @Override
        public boolean contains(final Object o) {
            if (!(o instanceof Table.KeyEntry<?, ?> entry)) {
                return false;
            }
            return HashTable.this.containsKey((R) entry.row(), (C) entry.column());
        }

        @Override
        public Iterator<KeyEntry<R, C>> iterator() {
            return new KeyIterator();
        }

        @Override
        public Object [] toArray() {
            return keysToArray(new Object[HashTable.this.size()]);
        }

        @Override
        public <T> T [] toArray(final T [] ts) {
            return keysToArray(HashTable.this.prepareArray(ts));
        }

        @Override
        public boolean add(final Table.KeyEntry<R, C> rcKeyEntry) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean remove(final Object o) {
            if (!(o instanceof Table.KeyEntry<?, ?> entry)) {
                return false;
            }
            return HashTable.this.remove((R) entry.row(), (C) entry.column()) != null;
        }

        @Override
        public boolean containsAll(final Collection<?> collection) {
            for (Object o : collection) {
                if (!(o instanceof Table.KeyEntry<?, ?> entry)) {
                    return false;
                }
                if (!HashTable.this.containsKey((R) entry.row(), (C) entry.column())) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public boolean addAll(final Collection<? extends KeyEntry<R, C>> collection) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean retainAll(final Collection<?> collection) {
            for (Object o : collection) {
                if (!(o instanceof Table.KeyEntry<?, ?> entry)) {
                    continue;
                }
                if (HashTable.this.containsKey((R) entry.row(), (C) entry.column())) {
                    continue;
                }
                HashTable.this.remove((R) entry.row(), (C) entry.column());
            }
            return true;
        }

        @Override
        public boolean removeAll(final Collection<?> collection) {
            for (Object o : collection) {
                if (!(o instanceof Table.KeyEntry<?, ?> entry)) {
                    continue;
                }
                HashTable.this.remove((R) entry.row(), (C) entry.column());
            }
            return true;
        }

        @Override
        public void clear() {
            HashTable.this.clear();
        }
    }

    private <T> T[] keysToArray(final T[] array) {
        int i = 0;
        while (i < array.length) {
            Table.Entry<R, C, V> entry = HashTable.this.table[i];
            array[i] = (T) entry.row();
        }
        return array;
    }

    private <T> T[] valuesToArray(final T[] array) {
        int i = 0;
        while (i < array.length) {
            Table.Entry<R, C, V> entry = HashTable.this.table[i];
            array[i] = (T) entry.value();
        }
        return array;
    }

    private <T> T[] entriesToArray(final T[] array) {
        int i = 0;
        while (i < array.length) {
            Table.Entry<R, C, V> entry = HashTable.this.table[i];
            array[i] = (T) entry;
        }
        return array;
    }

    final <T> T[] prepareArray(T[] a) {
        int size = this.size;
        if (a.length < size) {
            return (T[]) Array.newInstance(a.getClass().getComponentType(), size);
        } else {
            if (a.length > size) {
                a[size] = null;
            }

            return a;
        }
    }

    public class RowMap implements Map<C, V> {
        protected final R row;

        public RowMap(final R row) {
            this.row = row;
        }

        @Override
        public int size() {
            return HashTable.this.size();
        }

        @Override
        public boolean isEmpty() {
            return HashTable.this.isEmpty();
        }

        @Override
        public boolean containsKey(final Object o) {
            return HashTable.this.containsKey(row, (C) o);
        }

        @Override
        public boolean containsValue(final Object o) {
            return HashTable.this.containsValue((V) o);
        }

        @Override
        public V get(final Object o) {
            return HashTable.this.get(row, (C) o);
        }

        @Override
        public V put(final C c, final V v) {
            return HashTable.this.put(row, c, v);
        }

        @Override
        public V remove(final Object o) {
            return HashTable.this.remove(row, (C) o);
        }

        @Override
        public void putAll(final Map<? extends C, ? extends V> map) {
            for (Entry<? extends C, ? extends V> entry : map.entrySet()) {
                HashTable.this.put(row, entry.getKey(), entry.getValue());
            }
        }

        @Override
        public void clear() {
            Arrays.fill(HashTable.this.table, null);
            HashTable.this.size = 0;
            HashTable.this.modCount++;
        }

        @Override
        public Set<C> keySet() {
            return new RowMapKeySet(row);
        }

        @Override
        public Collection<V> values() {
            return new RowMapValues(row);
        }

        @Override
        public Set<Entry<C, V>> entrySet() {
            return new RowMapEntries(row);
        }
    }

    public class RowMapValues implements Collection<V> {
        private final R row;

        public RowMapValues(final R row) {
            this.row = row;
        }

        @Override
        public int size() {
            return HashTable.this.size();
        }

        @Override
        public boolean isEmpty() {
            return HashTable.this.isEmpty();
        }

        @Override
        public boolean contains(final Object o) {
            return HashTable.this.containsValue((V) o);
        }

        @Override
        public Iterator<V> iterator() {
            return new RowMapValueIterator(row);
        }

        @Override
        public Object [] toArray() {
            return valuesToArray(new Object[HashTable.this.size()]);
        }

        @Override
        public <T> T [] toArray(final T [] ts) {
            return valuesToArray(HashTable.this.prepareArray(ts));
        }

        @Override
        public boolean add(final V v) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean remove(final Object o) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean containsAll(final Collection<?> collection) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean addAll(final Collection<? extends V> collection) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean removeAll(final Collection<?> collection) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean retainAll(final Collection<?> collection) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void clear() {
            HashTable.this.clear();
        }
    }

    public class RowMapKeySet implements Set<C> {
        private final R row;

        public RowMapKeySet(final R row) {
            this.row = row;
        }

        @Override
        public int size() {
            return HashTable.this.size();
        }

        @Override
        public boolean isEmpty() {
            return HashTable.this.isEmpty();
        }

        @Override
        public boolean contains(final Object o) {
            return HashTable.this.containsKey(row, (C) o);
        }

        @Override
        public Iterator<C> iterator() {
            return new RowMapColumnIterator(row);
        }

        @Override
        public Object [] toArray() {
            return valuesToArray(new Object[HashTable.this.size()]);
        }

        @Override
        public <T> T [] toArray(final T [] ts) {
            return valuesToArray(HashTable.this.prepareArray(ts));
        }

        @Override
        public boolean add(final C v) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean remove(final Object o) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean containsAll(final Collection<?> collection) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean addAll(final Collection<? extends C> collection) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean removeAll(final Collection<?> collection) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean retainAll(final Collection<?> collection) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void clear() {
            HashTable.this.clear();
        }
    }

    public class RowMapEntries implements Set<Map.Entry<C, V>> {
        final R row;

        public RowMapEntries(final R row) {
            this.row = row;
        }

        @Override
        public int size() {
            return HashTable.this.size();
        }

        @Override
        public boolean isEmpty() {
            return HashTable.this.isEmpty();
        }

        @Override
        public boolean contains(final Object o) {
            return HashTable.this.containsValue((V) o);
        }

        @Override
        public Iterator<Map.Entry<C, V>> iterator() {
            return new RowMapEntryIterator(row);
        }

        @Override
        public Object [] toArray() {
            return valuesToArray(new Object[HashTable.this.size()]);
        }

        @Override
        public <T> T [] toArray(final T [] ts) {
            return valuesToArray(HashTable.this.prepareArray(ts));
        }

        @Override
        public boolean add(final Map.Entry<C, V> v) {
            return HashTable.this.put(row, v.getKey(), v.getValue()) != null;
        }

        @Override
        public boolean remove(final Object o) {
            return HashTable.this.remove(row, (C) o) != null;
        }

        @Override
        public boolean containsAll(final Collection<?> collection) {
            for (Object o : collection) {
                if (!(o instanceof Map.Entry<?, ?> entry)) continue;
                if (!HashTable.this.containsKey(row, (C) entry.getKey())) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public boolean addAll(final Collection<? extends Map.Entry<C, V>> collection) {
            for (Object o : collection) {
                if (!(o instanceof Map.Entry<?, ?> entry)) {
                    continue;
                }
                HashTable.this.put(row, (C) entry.getKey(), (V) entry.getValue());
            }
            return true;
        }

        @Override
        public boolean removeAll(final Collection<?> collection) {
            for (Object o : collection) {
                if (!(o instanceof Map.Entry<?, ?> entry)) {
                    continue;
                }
                HashTable.this.remove(row, (C) entry.getKey());
            }
            return true;
        }

        @Override
        public boolean retainAll(final Collection<?> collection) {
            for (Object o : collection) {
                if (!(o instanceof Map.Entry<?, ?> entry)) {
                    continue;
                }
                if (collection.contains(HashTable.this.get(row, (C) entry.getKey()))) {
                    continue;
                }
                HashTable.this.remove(row, (C) entry.getKey());
            }
            return true;
        }

        @Override
        public void clear() {
            HashTable.this.clear();
        }
    }

    public class RowMapEntryIterator implements Iterator<Map.Entry<C, V>> {
        private int index = 0; // Index in the table
        private final int expectedModCount = modCount; // Capture modCount at iterator creation
        private Table.Entry<R, C, V> currentNode = null;
        private final R row;

        public RowMapEntryIterator(final R row) {
            this.row = row;
        }

        @Override
        public boolean hasNext() {
            if (expectedModCount != modCount) {
                throw new ConcurrentModificationException();
            }
            while (currentNode == null && index < table.length) {
                Table.Entry<R, C, V> node = table[index++];
                if (node != null && node.row().equals(row)) {
                    currentNode = node;
                    break;
                }
            }
            return currentNode != null;
        }

        @Override
        public Map.Entry<C, V> next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }

            Table.Entry<R, C, V> entry = currentNode;
            currentNode = currentNode.next(); // Move to the next node in the chain
            return Map.entry(entry.column(), entry.value());
        }
    }

    public class RowMapColumnIterator implements Iterator<C> {
        private final RowMapEntryIterator iterator;

        public RowMapColumnIterator(R row) {
            this.iterator = new RowMapEntryIterator(row);
        }

        @Override
        public boolean hasNext() {
            return iterator.hasNext();
        }

        @Override
        public C next() {
            Map.Entry<C, V> entry = iterator.next();
            return entry.getKey();
        }
    }

    public class RowMapValueIterator implements Iterator<V> {
        private final RowMapEntryIterator iterator;

        public RowMapValueIterator(R row) {
            this.iterator = new RowMapEntryIterator(row);
        }

        @Override
        public boolean hasNext() {
            return iterator.hasNext();
        }

        @Override
        public V next() {
            Map.Entry<C, V> entry = iterator.next();
            return entry.getValue();
        }
    }

    public class EntrySet implements Set<Entry<R, C, V>> {

        @Override
        public int size() {
            return HashTable.this.size();
        }

        @Override
        public boolean isEmpty() {
            return HashTable.this.isEmpty();
        }

        @Override
        public boolean contains(final Object o) {
            return HashTable.this.containsKey((R) ((Table.Entry<?, ?, ?>) o).row(), (C) ((Table.Entry<?, ?, ?>) o).column());
        }

        @Override
        public Iterator<Entry<R, C, V>> iterator() {
            return new EntryIterator();
        }

        @Override
        public Object [] toArray() {
            return entriesToArray(new Object[HashTable.this.size()]);
        }

        @Override
        public <T> T [] toArray(final T [] ts) {
            return entriesToArray(HashTable.this.prepareArray(ts));
        }

        @Override
        public boolean add(final Table.Entry<R, C, V> rcvEntry) {
            return HashTable.this.putValue(rcvEntry, false) != null;
        }

        @Override
        public boolean remove(final Object o) {
            if (!(o instanceof Table.Entry<?, ?, ?> entry)) {
                return false;
            }
            return HashTable.this.remove((R) entry.row(), (C) entry.column()) != null;
        }

        @Override
        public boolean containsAll(final Collection<?> collection) {
            for (Object o : collection) {
                if (!(o instanceof Table.Entry<?, ?, ?> entry)) {
                    continue;
                }
                if (!HashTable.this.containsKey((R) entry.row(), (C) entry.column())) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public boolean addAll(final Collection<? extends Entry<R, C, V>> collection) {
            for (Table.Entry<R, C, V> entry : collection) {
                HashTable.this.putValue(entry, false);
            }
            return true;
        }

        @Override
        public boolean retainAll(final Collection<?> collection) {
            for (Object o : collection) {
                if (!(o instanceof Table.Entry<?, ?, ?> entry)) {
                    continue;
                }
                if (collection.contains(HashTable.this.get((R) entry.row(), (C) entry.column()))) {
                    continue;
                }
                HashTable.this.remove((R) entry.row(), (C) entry.column());
            }
            return true;
        }

        @Override
        public boolean removeAll(final Collection<?> collection) {
            for (Object o : collection) {
                if (!(o instanceof Table.Entry<?, ?, ?> entry)) {
                    continue;
                }
                HashTable.this.remove((R) entry.row(), (C) entry.column());
            }
            return true;
        }

        @Override
        public void clear() {
            HashTable.this.clear();
        }
    }

    public class Values implements Collection<V> {

        @Override
        public int size() {
            return HashTable.this.size();
        }

        @Override
        public boolean isEmpty() {
            return HashTable.this.isEmpty();
        }

        @Override
        public boolean contains(final Object o) {
            return HashTable.this.containsValue((V) o);
        }

        @Override
        public Iterator<V> iterator() {
            return new ValueIterator();
        }

        @Override
        public Object [] toArray() {
            return valuesToArray(new Object[HashTable.this.size()]);
        }

        @Override
        public <T> T [] toArray(final T [] ts) {
            return valuesToArray(HashTable.this.prepareArray(ts));
        }

        @Override
        public boolean add(final V v) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean remove(final Object o) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean containsAll(final Collection<?> collection) {
            for (Object o : collection) {
                if (!HashTable.this.containsValue((V) o)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public boolean addAll(final Collection<? extends V> collection) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean removeAll(final Collection<?> collection) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean retainAll(final Collection<?> collection) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void clear() {
            throw new UnsupportedOperationException();
        }
    }

    public class KeyIterator implements Iterator<KeyEntry<R, C>> {
        private final EntryIterator iterator = new EntryIterator();

        @Override
        public boolean hasNext() {
            return iterator.hasNext();
        }

        @Override
        public Table.KeyEntry<R, C> next() {
            return iterator.next().key();
        }

        @Override
        public void remove() {
            iterator.remove();
        }
    }

    public class ValueIterator implements Iterator<V> {
        private final EntryIterator iterator = new EntryIterator();

        @Override
        public boolean hasNext() {
            return iterator.hasNext();
        }

        @Override
        public V next() {
            return iterator.next().value();
        }

        @Override
        public void remove() {
            iterator.remove();
        }
    }

    public class EntryIterator implements Iterator<Entry<R, C, V>> {
        private int index = 0; // Index in the table
        private final int expectedModCount = modCount; // Capture modCount at iterator creation
        private Table.Entry<R, C, V> currentNode = null;

        @Override
        public boolean hasNext() {
            if (expectedModCount != modCount) {
                throw new ConcurrentModificationException();
            }
            while (currentNode == null && index < table.length) {
                currentNode = table[index++];
            }
            return currentNode != null;
        }

        @Override
        public Table.Entry<R, C, V> next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }

            Table.Entry<R, C, V> entry = currentNode;
            currentNode = currentNode.next(); // Move to the next node in the chain
            return entry;
        }
    }
}
