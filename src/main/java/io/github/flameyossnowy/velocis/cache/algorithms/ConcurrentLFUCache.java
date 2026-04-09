package io.github.flameyossnowy.velocis.cache.algorithms;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.StampedLock;

import static io.github.flameyossnowy.velocis.cache.algorithms.ConcurrentLFRUCache.probe0;

/**
 * Low-level, parallel-array, open-addressing LFU cache.
 *
 * <h2>Layout</h2>
 * Four flat arrays indexed by the same slot index {@code i}:
 * <pre>
 *   int[]    hashes    — spread hash; 0 = empty, -1 = tombstone
 *   Object[] keys      — key at slot i
 *   Object[] values    — value at slot i
 *   int[]    freqs     — access frequency at slot i
 * </pre>
 *
 * Open addressing with linear probing keeps everything in one cache line
 * neighbourhood, which is the key difference from the original
 * {@code ConcurrentHashMap}-backed version.
 *
 * <h2>Concurrency</h2>
 * A single {@link StampedLock} guards mutations; reads use an optimistic
 * read stamp with fallback to a read lock.  This is appropriate for
 * read-heavy caches where eviction is rare.
 *
 * <h2>Eviction</h2>
 * On every {@link #put} that would exceed capacity the lowest-frequency
 * slot is evicted.  A linear scan of {@code freqs[]} is O(n) but n is
 * small (cache sizes are typically ≤ 10 000) and the array is extremely
 * cache-friendly, so in practice it outperforms a priority-queue approach
 * for small caches.
 */
@SuppressWarnings("unchecked")
public class ConcurrentLFUCache<K, V> implements Map<K, V> {

    private static final int   EMPTY     =  0;
    private static final int   TOMBSTONE = -1;
    private static final float LOAD      = 0.70f; // keep array < 70% full

    private int[]    hashes;   // spread hash | 0=empty | -1=tombstone
    private Object[] keys;
    private Object[] values;
    private int[]    freqs;

    private int capacity;   // always a power of two
    private int mask;       // capacity - 1
    private final int maxSize;

    private final StampedLock lock = new StampedLock();
    private final AtomicInteger liveCount = new AtomicInteger(0);

    public ConcurrentLFUCache(int maxSize) {
        if (maxSize <= 0) throw new IllegalArgumentException("maxSize must be > 0");
        this.maxSize  = maxSize;
        this.capacity = nextPow2((int) (maxSize / LOAD) + 1);
        this.mask     = capacity - 1;
        this.hashes   = new int   [capacity];
        this.keys     = new Object[capacity];
        this.values   = new Object[capacity];
        this.freqs    = new int   [capacity];
    }

    public ConcurrentLFUCache() { this(16); }
    public ConcurrentLFUCache(int maxSize, int ignoredConcurrencyLevel) { this(maxSize); }
    public ConcurrentLFUCache(int maxSize, float ignoredLoadFactor) { this(maxSize); }
    public ConcurrentLFUCache(int maxSize, int i, float f) { this(maxSize); }

    private static int spread(int h) {
        h ^= (h >>> 16);
        h &= 0x7FFF_FFFF; // keep positive; 0 and -1 are sentinels
        return h == 0 ? 1 : h;
    }

    @Override
    public @Nullable V get(Object key) {
        int h   = spread(key.hashCode());
        long stamp = lock.tryOptimisticRead();

        int idx = findSlot(h, key, hashes, keys, mask);
        V   val = idx >= 0 ? (V) values[idx] : null;

        if (!lock.validate(stamp)) {
            // Fallback to read lock
            stamp = lock.readLock();
            try {
                idx = findSlot(h, key, hashes, keys, mask);
                val = idx >= 0 ? (V) values[idx] : null;
            } finally {
                lock.unlockRead(stamp);
            }
        }

        if (idx >= 0) {
            // Increment frequency under write lock (small contention window)
            long ws = lock.writeLock();
            try {
                // Re-find: slot may have moved between read and write lock
                int widx = findSlot(h, key, hashes, keys, mask);
                if (widx >= 0) freqs[widx]++;
            } finally {
                lock.unlockWrite(ws);
            }
        }

        return val;
    }

    @Override
    public @Nullable V put(K key, V value) {
        if (value == null) throw new IllegalArgumentException("Null values not allowed");
        int h = spread(key.hashCode());

        long stamp = lock.writeLock();
        try {
            int idx = findSlot(h, key, hashes, keys, mask);
            if (idx >= 0) {
                // Update existing
                V old = (V) values[idx];
                values[idx] = value;
                freqs [idx]++;
                return old;
            }

            // Evict if at capacity
            if (liveCount.get() >= maxSize) evictLFU();

            // Insert into first empty or tombstone slot
            int slot = probeInsert(h, hashes, mask);
            hashes[slot] = h;
            keys  [slot] = key;
            values[slot] = value;
            freqs [slot] = 1;
            liveCount.incrementAndGet();
            return null;
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    @Override
    public @Nullable V remove(@NotNull Object key) {
        int h = spread(key.hashCode());
        long stamp = lock.writeLock();
        try {
            int idx = findSlot(h, key, hashes, keys, mask);
            if (idx < 0) return null;
            V old      = (V) values[idx];
            hashes[idx] = TOMBSTONE;
            keys  [idx] = null;
            values[idx] = null;
            freqs [idx] = 0;
            liveCount.decrementAndGet();
            return old;
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    private void evictLFU() {
        int minFreq  = Integer.MAX_VALUE;
        int minSlot  = -1;
        int[] fs     = freqs;
        int[] hs     = hashes;
        int   cap    = capacity;

        for (int i = 0; i < cap; i++) {
            if (hs[i] > 0 && fs[i] < minFreq) {
                minFreq = fs[i];
                minSlot = i;
            }
        }

        if (minSlot >= 0) {
            hashes[minSlot] = TOMBSTONE;
            keys  [minSlot] = null;
            values[minSlot] = null;
            freqs [minSlot] = 0;
            liveCount.decrementAndGet();
        }
    }

    /** Returns slot index if key found, -1 otherwise. */
    private static int findSlot(int h, Object key, int[] hs, Object[] ks, int mask) {
        int i = h & mask;
        for (int probe = 0; probe <= mask; probe++) {
            int sh = hs[i];
            if (sh == EMPTY)      return -1;
            if (sh == h && key.equals(ks[i])) return i;
            i = (i + 1) & mask;
        }
        return -1;
    }

    /** Returns the first empty or tombstone slot for insertion. */
    private static int probeInsert(int h, int[] hs, int mask) {
        return probe0(h, hs, mask, EMPTY, TOMBSTONE);
    }

    @Override public void putAll(Map<? extends K, ? extends V> m) {
        for (Entry<? extends K, ? extends V> entry : m.entrySet()) {
            K key = entry.getKey();
            V value = entry.getValue();
            put(key, value);
        }
    }

    @Override
    public void clear() {
        long stamp = lock.writeLock();
        try {
            Arrays.fill(hashes, EMPTY);
            Arrays.fill(keys,   null);
            Arrays.fill(values, null);
            Arrays.fill(freqs,  EMPTY);
            liveCount.set(EMPTY);
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    @Override public int     size()                    { return liveCount.get(); }
    @Override public boolean isEmpty()                 { return liveCount.get() == 0; }
    @Override public boolean containsKey(Object key)  { return get(key) != null; }
    @Override public boolean containsValue(Object v)  {
        long stamp = lock.readLock();
        try {
            for (int i = 0; i < capacity; i++)
                if (hashes[i] > 0 && v.equals(values[i])) return true;
            return false;
        } finally {
            lock.unlockRead(stamp);
        }
    }

    @Override public @NotNull Set<K>          keySet()   { return new KeySetView(); }
    @Override public @NotNull Collection<V>   values()   { return new ValuesView(); }
    @Override public @NotNull Set<Entry<K, V>> entrySet() { return new EntrySetView(); }

    private abstract class SlotIterator<T> implements Iterator<T> {
        int cursor = 0;
        int next   = -1;

        SlotIterator() { advance(); }

        private void advance() {
            while (cursor < capacity && hashes[cursor] <= 0) cursor++;
            next = cursor < capacity ? cursor++ : -1;
        }

        @Override public boolean hasNext() { return next >= 0; }

        @Override public T next() {
            if (next < 0) throw new NoSuchElementException();
            int i = next;
            advance();
            return extract(i);
        }

        abstract T extract(int i);
    }

    private final class KeySetView extends AbstractSet<K> {
        @Override public int size() { return liveCount.get(); }
        @Override public @NotNull Iterator<K> iterator() {
            long stamp = lock.readLock();
            try {
                return new SlotIterator<K>() {
                    @Override K extract(int i) {
                        return (K) keys[i];
                    }
                };
            } finally {
                lock.unlockRead(stamp);
            }
        }
        @Override public boolean contains(Object o) { return containsKey(o); }
    }

    private final class ValuesView extends AbstractCollection<V> {
        @Override public int size() { return liveCount.get(); }
        @Override public @NotNull Iterator<V> iterator() {
            long stamp = lock.readLock();
            try {
                return new SlotIterator<V>() {
                    @Override V extract(int i) {
                        return (V) values[i];
                    }
                };
            } finally {
                lock.unlockRead(stamp);
            }
        }
    }

    private final class EntrySetView extends AbstractSet<Entry<K,V>> {
        @Override public int size() { return liveCount.get(); }
        @Override public @NotNull Iterator<Entry<K,V>> iterator() {
            long stamp = lock.readLock();
            try {
                return new SlotIterator<Entry<K,V>>() {
                    @Override Entry<K,V> extract(int i) {
                        return Map.entry((K) keys[i], (V) values[i]);
                    }
                };
            } finally {
                lock.unlockRead(stamp);
            }
        }
    }

    public static int nextPow2(int n) {
        if (n <= 1) return 2;
        n--;
        n |= n >>> 1; n |= n >>> 2; n |= n >>> 4; n |= n >>> 8; n |= n >>> 16;
        return n + 1;
    }
}