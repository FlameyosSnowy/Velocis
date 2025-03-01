package io.github.flameyossnowy.velocis.cache.multimap;

public final class Multimaps {
    private Multimaps() {
        throw new UnsupportedOperationException();
    }

    public static <K, V> Multimap<K, V> newMultimap() {
        return new NormalMultimap<>();
    }

    public static <K, V> Multimap<K, V> newConcurrentMultimap() {
        return new ConcurrentMultimap<>();
    }

    public static <K, V> Multimap<K, V> newMultimap(int size) {
        return new NormalMultimap<>(size);
    }

    public static <K, V> Multimap<K, V> newConcurrentMultimap(int size) {
        return new ConcurrentMultimap<>(size);
    }

    public static <K, V> Multimap<K, V> newMultimap(int size, float loadFactor) {
        return new NormalMultimap<>(size, loadFactor);
    }

    public static <K, V> Multimap<K, V> newConcurrentMultimap(int size, float loadFactor) {
        return new ConcurrentMultimap<>(size, loadFactor);
    }

    public static <K, V> Multimap<K, V> newConcurrentMultimap(int size, int concurrencyLevel) {
        return new ConcurrentMultimap<>(size, concurrencyLevel);
    }

    public static <K, V> Multimap<K, V> newConcurrentMultimap(int size, int concurrencyLevel, float loadFactor) {
        return new ConcurrentMultimap<>(size, loadFactor, concurrencyLevel);
    }

    public static <K, V> Multimap<K, V> newMultimap(AlgorithmType algorithmType) {
        return switch (algorithmType) {
            case LFU -> new LFUMultimap<>();
            case LRU -> new LRUMultimap<>();
            case LFRU -> new LFRUMultimap<>();
            case CONCURRENT_LFU -> new ConcurrentLFUMultimap<>();
            case CONCURRENT_LRU -> new ConcurrentLRUMultimap<>();
            case CONCURRENT_LFRU -> new ConcurrentLFRUMultimap<>();
        };
    }

    public static <K, V> Multimap<K, V> newConcurrentMultimap(AlgorithmType algorithmType, int maxSize, int concurrencyLevel, float loadFactor) {
        return switch (algorithmType) {
            case CONCURRENT_LFU -> new ConcurrentLFUMultimap<>(maxSize, concurrencyLevel, loadFactor);
            case CONCURRENT_LRU -> new ConcurrentLRUMultimap<>(maxSize, concurrencyLevel, loadFactor);
            case CONCURRENT_LFRU -> new ConcurrentLFRUMultimap<>(maxSize, concurrencyLevel, loadFactor);
            default -> throw new UnsupportedOperationException();
        };
    }

    public static <K, V> Multimap<K, V> newConcurrentMultimap(AlgorithmType algorithmType, int maxSize, int concurrencyLevel) {
        return newConcurrentMultimap(algorithmType, maxSize, concurrencyLevel, 0.75F);
    }

    public static <K, V> Multimap<K, V> newConcurrentMultimap(AlgorithmType algorithmType, int maxSize, float loadFactor) {
        return newConcurrentMultimap(algorithmType, maxSize, 1, loadFactor);
    }

    public static <K, V> Multimap<K, V> newConcurrentMultimap(AlgorithmType algorithmType, int maxSize) {
        return newConcurrentMultimap(algorithmType, maxSize, 1, 0.75F);
    }

    public enum AlgorithmType {
        LFU,
        LRU,
        LFRU,
        CONCURRENT_LFU,
        CONCURRENT_LRU,
        CONCURRENT_LFRU
    }
}
