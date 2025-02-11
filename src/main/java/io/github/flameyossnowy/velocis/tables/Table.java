package io.github.flameyossnowy.velocis.tables;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

public interface Table<R, C, V> {
    boolean containsKey(R row, C column);

    boolean containsValue(V value);

    boolean contains(R row, C column, V value);

    V getOrDefault(R row, C column, V defaultValue);

    V remove(R row, C column, V value);

    Map<C, V> row(R row);

    void forEach(ForEach<? super R, ? super C, ? super V> action);

    /**
     * Returns the number of elements in the table.
     *
     * @return the size of the table
     */
    int size();

    /**
     * Removes all mappings from the table.
     */
    void clear();

    /**
     * Returns {@code true} if the table contains no mappings, {@code false} otherwise.
     *
     * @return true if the table contains no mappings, false otherwise
     */
    boolean isEmpty();

    /**
     * Returns the value associated with the given row and column.
     *
     * @param row   the row key
     * @param column the column key
     * @return the associated value, or null if there is no association
     */
    V get(R row, C column);

    /**
     * Associates the specified value with the specified row and column in the table.
     * If the table previously contained a mapping for the specified row and column,
     * the old value is replaced by the specified value.
     *
     * @param row    the row key with which the specified value is to be associated
     * @param column the column key with which the specified value is to be associated
     * @param value  the value to be associated with the specified row and column
     */
    V put(R row, C column, V value);

    /**
     * Removes the mapping for the specified row and column if present.
     *
     * @param row   the row key to remove
     * @param column the column key to remove
     * @return the value associated with the row and column or null if absent
     */
    V remove(R row, C column);

    /**
     * Copies all mappings from the specified table to this table.
     * The effect of this call is equivalent to that of calling {@link #put} on this table
     * once for each mapping from the specified table.
     * If the specified table is this table, then this method has no effect.
     *
     * @param table the table to copy mappings from
     */
    void putAll(Table<R, C, V> table);

    /**
     * Returns a set view of the mappings contained in this table. The set is
     * backed by the table, so changes to the table are reflected in the set, and
     * vice versa. The set supports element removal, which removes the corresponding
     * mapping from the table, via the {@code Iterator.remove()},
     * {@code Set.remove()}, {@code removeAll()}, {@code retainAll()}, and
     * {@code clear()} operations. It does not support the {@code add()} or
     * {@code addAll()} operations.
     *
     * @return a set view of the mappings contained in this table
     */
    Set<Entry<R, C, V>> entrySet();

    /**
     * Returns a set view of the keys contained in this table. The set is
     * backed by the table, so changes to the table are reflected in the set, and
     * vice versa. The set supports element removal, which removes the corresponding
     * mapping from the table, via the {@code Iterator.remove()},
     * {@code Set.remove()}, {@code removeAll()}, {@code retainAll()}, and
     * {@code clear()} operations. It does not support the {@code add()} or
     * {@code addAll()} operations.
     *
     * @return a set view of the keys contained in this table
     */
    Set<KeyEntry<R, C>> keySet();

    /**
     * Returns a collection view of the values contained in this table. The collection is
     * backed by the table, so changes to the table are reflected in the collection, and
     * vice versa. The collection supports element removal, which removes the corresponding
     * mapping from the table, via the {@code Iterator.remove()},
     * {@code Collection.remove()}, and {@code clear()} operations. It does not support the
     * {@code add()} or {@code addAll()} operations.
     *
     * @return a collection view of the values contained in this table
     */
    Collection<V> values();

    /**
     * If the specified row and column is not already associated with a value
     * (or is mapped to {@code null}), associates it with the given value and
     * returns the associated value, else returns the current value.
     *
     * @param row    the row key
     * @param column the column key
     * @param value  the value to be associated with the specified row and column
     * @return the current (existing or newly inserted) value associated with the
     * specified key, or null if the specified key is not mapped to any value
     */
    V putIfAbsent(R row, C column, V value);

    /**
     * If the specified row and column is not already associated with a value
     * (or is mapped to {@code null}), associates it with the given value and
     * returns the associated value, else returns the current value.
     *
     * @param row             the row key
     * @param column          the column key
     * @param mappingFunction the function to compute a value
     * @return the current (existing or newly inserted) value associated with the
     * specified key, or null if the specified key is not mapped to any value
     */
    default V computeIfAbsent(R row, C column, BiFunction<? super R, ? super C, ? extends V> mappingFunction) {
        V value = get(row, column);
        if (value == null) {
            value = mappingFunction.apply(row, column);
            put(row, column, value);
        }
        return value;
    }

    /**
     * If the value for the specified row and column is present, attempts to
     * compute a new mapping given the current value.
     *
     * @param row             the row key
     * @param column          the column key
     * @param remappingFunction the function to compute a value
     * @return the new value associated with the specified key, or the current value
     * if the computation did not change the mapping
     */
    default V computeIfPresent(R row, C column, BiFunction<? super R, ? super C, ? extends V> remappingFunction) {
        V value = get(row, column);
        if (value != null) {
            value = remappingFunction.apply(row, column);
            put(row, column, value);
        }
        return value;
    }

    /**
     * Attempts to compute a new mapping given the current value.
     * If the remapped value is null, the mapping is removed.
     * If the remapped value is non-null, the new value will replace the old value.
     *
     * @param row             the row key
     * @param column          the column key
     * @param remappingFunction the function to compute a value
     * @return the new value associated with the specified key, or the current value
     * if the computation did not change the mapping
     */
    default V compute(R row, C column, BiFunction<? super R, ? super C, ? extends V> remappingFunction) {
        put(row, column, remappingFunction.apply(row, column));
        return get(row, column);
    }

    interface KeyEntry<R, C> {
        /**
         * Returns the row key associated with this entry.
         *
         * @return the row key
         */
        R row();

        /**
         * Returns the column key associated with this entry.
         *
         * @return the column key
         */
        C column();
    }

    interface Entry<R, C, V> {
        /**
         * Returns the row key associated with this entry.
         *
         * @return the row key
         */
        R row();

        /**
         * Returns the column key associated with this entry.
         *
         * @return the column key
         */
        C column();

        /**
         * Returns the value associated with this entry.
         *
         * @return the value
         */
        V value();

        Entry<R, C, V> next();

        /**
         * Returns an immutable {@link KeyEntry} view of the mapping represented
         * by this entry. This method is provided for use in access by {@link
         * Entry}-consuming methods.
         *
         */
        KeyEntry<R, C> key();

        void setNext(Entry<R, C, V> rcvNode);

        void setValue(V value);
    }

    static <R, C, V> Table.Entry<R, C, V> entry(R row, C column, V value) {
        return new Node<>(row, column, value);
    }

    class Node<R, C, V> implements Entry<R, C, V> {
        final R row;
        final C column;
        V value;

        Entry<R, C, V> next;

        public Node(R row, C column, V value) {
            this.row = row;
            this.column = column;
            this.value = value;
        }

        @Override
        public R row() {
            return row;
        }

        @Override
        public C column() {
            return column;
        }

        @Override
        public V value() {
            return value;
        }

        @Override
        public Entry<R, C, V> next() {
            return next;
        }

        @Override
        public void setNext(Entry<R, C, V> rcvNode) {
            this.next = rcvNode;
        }

        @Override
        public void setValue(final V value) {
            this.value = value;
        }

        @Override
        public KeyEntry<R, C> key() {
            return new KeyNode<>(row, column);
        }
    }

    record KeyNode<R, C>(R row, C column) implements KeyEntry<R, C> {
    }

    @SafeVarargs
    static <R, C, V> Table<R, C, V> of(Entry<R, C, V>... entries) {
        Table<R, C, V> table = new HashTable<>();
        for (Entry<R, C, V> entry : entries) {
            table.put(entry.row(), entry.column(), entry.value());
        }
        return table;
    }

    static <R, C, V> Table<R, C, V> immutable(Table<R, C, V> table) {
        return new ImmutableTable<>(table);
    }

    class ImmutableTable<R, C, V> implements Table<R, C, V> {
        private final Table<R, C, V> table;

        public ImmutableTable(Table<R, C, V> table) {
            this.table = table;
        }

        @Override
        public boolean containsKey(R row, C column) {
            return table.containsKey(row, column);
        }

        @Override
        public boolean containsValue(V value) {
            return table.containsValue(value);
        }

        @Override
        public boolean contains(R row, C column, V value) {
            return table.contains(row, column, value);
        }

        @Override
        public V getOrDefault(R row, C column, V defaultValue) {
            return table.getOrDefault(row, column, defaultValue);
        }

        @Override
        public V remove(R row, C column, V value) {
            return table.remove(row, column, value);
        }

        @Override
        public Map<C, V> row(final R row) {
            return Map.of();
        }

        @Override
        public void forEach(ForEach<? super R, ? super C, ? super V> action) {
            table.forEach(action);
        }

        @Override
        public int size() {
            return table.size();
        }

        @Override
        public void clear() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isEmpty() {
            return table.isEmpty();
        }

        @Override
        public V get(R row, C column) {
            return table.get(row, column);
        }

        @Override
        public V put(R row, C column, V value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public V putIfAbsent(R row, C column, V value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public V remove(final R row, final C column) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void putAll(Table<R, C, V> table) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Set<Entry<R, C, V>> entrySet() {
            return table.entrySet();
        }

        @Override
        public Set<KeyEntry<R, C>> keySet() {
            return table.keySet();
        }

        @Override
        public Collection<V> values() {
            return table.values();
        }
    }

    @FunctionalInterface
    interface ForEach<R, C, V> {
        void accept(R row, C column, V value);
    }
}
