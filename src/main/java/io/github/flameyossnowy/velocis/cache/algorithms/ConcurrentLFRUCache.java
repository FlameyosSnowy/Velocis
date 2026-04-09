package io.github.flameyossnowy.velocis.cache.algorithms;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.StampedLock;

/**
 * Low-level, parallel-array, open-addressing LFRU (Least Frequently Recently Used) cache.
 *
 * <h2>Layout</h2>
 * Five flat arrays indexed by the same slot index {@code i}:
 * <pre>
 *   int[]    hashes    — spread hash; 0 = empty, -1 = tombstone
 *   Object[] keys      — key at slot i
 *   Object[] values    — value at slot i
 *   int[]    freqs     — access frequency at slot i
 *   long[]   lastUsed  — logical timestamp of last access (for recency tiebreaking)
 * </pre>
 *
 * <h2>LFRU policy</h2>
 * On eviction, the candidate with the <em>lowest frequency</em> is chosen.
 * Among equal-frequency candidates the <em>least recently used</em> wins,
 * i.e. the one with the smallest {@code lastUsed} timestamp.
 * This combines LFU correctness with LRU tiebreaking, avoiding the
 * "cache pollution" problem of pure LFU where a one-time burst can keep
 * a stale entry alive forever.
 *
 * <h2>Concurrency</h2>
 * {@link StampedLock} with optimistic reads, same model as
 * {@link ConcurrentLFUCache}.
 */
@SuppressWarnings("unchecked")
public class ConcurrentLFRUCache<K, V> implements Map<K, V> {

    private static final int   EMPTY     =  0;
    private static final int   TOMBSTONE = -1;
    private static final float LOAD      = 0.70f;

    private int[]    hashes;
    private K[] keys;
    private V[] values;
    private int[]    freqs;
    private long[]   lastUsed;

    private int capacity;
    private int mask;
    private final int maxSize;

    private final StampedLock   lock      = new StampedLock();
    private final AtomicInteger liveCount = new AtomicInteger(0);
    private long                clock     = 0L;

    public ConcurrentLFRUCache(int maxSize) {
        if (maxSize <= 0) throw new IllegalArgumentException("maxSize must be > 0");
        this.maxSize  = maxSize;
        this.capacity = nextPow2((int) (maxSize / LOAD) + 1);
        this.mask     = capacity - 1;
        allocArrays(capacity);
    }

    public ConcurrentLFRUCache()                               { this(16); }
    public ConcurrentLFRUCache(int s, int i)                   { this(s); }
    public ConcurrentLFRUCache(int s, float f)                 { this(s); }
    public ConcurrentLFRUCache(int s, int i, float f)          { this(s); }

    private void allocArrays(int cap) {
        hashes   = new int   [cap];
        keys     = (K[]) new Object[cap];
        values   = (V[]) new Object[cap];
        freqs    = new int   [cap];
        lastUsed = new long  [cap];
    }

    private static int spread(int h) {
        h ^= (h >>> 16);
        h &= 0x7FFF_FFFF;
        return h == 0 ? 1 : h;
    }

    @Override
    public @Nullable V get(Object key) {
        int h = spread(key.hashCode());

        // Optimistic read
        long stamp = lock.tryOptimisticRead();
        int  idx   = findSlot(h, key, hashes, keys, mask);
        V    val   = idx >= 0 ? (V) values[idx] : null;

        if (!lock.validate(stamp)) {
            stamp = lock.readLock();
            try {
                idx = findSlot(h, key, hashes, keys, mask);
                val = idx >= 0 ? (V) values[idx] : null;
            } finally {
                lock.unlockRead(stamp);
            }
        }

        if (idx >= 0) {
            long ws = lock.writeLock();
            try {
                int widx = findSlot(h, key, hashes, keys, mask);
                if (widx >= 0) {
                    freqs   [widx]++;
                    lastUsed[widx] = ++clock;
                }
            } finally {
                lock.unlockWrite(ws);
            }
        }

        return val;
    }

    @Override
    public @Nullable V put(K key, V value) {
        int h = spread(key.hashCode());

        long stamp = lock.writeLock();
        try {
            int idx = findSlot(h, key, hashes, keys, mask);
            if (idx >= 0) {
                V old       = (V) values[idx];
                values  [idx] = value;
                freqs   [idx]++;
                lastUsed[idx] = ++clock;
                return old;
            }

            if (liveCount.get() >= maxSize) evictLFRU();

            int slot = probeInsert(h, hashes, mask);
            hashes  [slot] = h;
            keys    [slot] = key;
            values  [slot] = value;
            freqs   [slot] = 1;
            lastUsed[slot] = ++clock;
            liveCount.incrementAndGet();
            return null;
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    @Override
    public @Nullable V remove(Object key) {
        int h = spread(key.hashCode());
        long stamp = lock.writeLock();
        try {
            int idx = findSlot(h, key, hashes, keys, mask);
            if (idx < 0) return null;
            V old        = (V) values[idx];
            hashes  [idx] = TOMBSTONE;
            keys    [idx] = null;
            values  [idx] = null;
            freqs   [idx] = 0;
            lastUsed[idx] = 0L;
            liveCount.decrementAndGet();
            return old;
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    private void evictLFRU() {
        int  minFreq     = Integer.MAX_VALUE;
        long minLastUsed = Long.MAX_VALUE;
        int  victim      = -1;

        int[]    hs = hashes;
        int[]    fs = freqs;
        long[]   lu = lastUsed;
        int      cap = capacity;

        for (int i = 0; i < cap; i++) {
            if (hs[i] <= 0) continue; // empty or tombstone
            int  f = fs[i];
            long t = lu[i];
            if (f < minFreq || (f == minFreq && t < minLastUsed)) {
                minFreq     = f;
                minLastUsed = t;
                victim      = i;
            }
        }

        if (victim >= 0) {
            hashes  [victim] = TOMBSTONE;
            keys    [victim] = null;
            values  [victim] = null;
            freqs   [victim] = 0;
            lastUsed[victim] = 0L;
            liveCount.decrementAndGet();
        }
    }

    private static int findSlot(int h, Object key, int[] hs, Object[] ks, int mask) {
        int i = h & mask;
        for (int p = 0; p <= mask; p++) {
            int sh = hs[i];
            if (sh == EMPTY)                        return -1;
            if (sh == h && key.equals(ks[i]))       return i;
            i = (i + 1) & mask;
        }
        return -1;
    }

    private static int probeInsert(int h, int[] hs, int mask) {
        return probe0(h, hs, mask, EMPTY, TOMBSTONE);
    }

    static int probe0(int h, int[] hs, int mask, int empty, int tombstone) {
        int i         = h & mask;
        int firstTomb = -1;
        for (int p = 0; p <= mask; p++) {
            int sh = hs[i];
            if (sh == empty)                          return firstTomb >= 0 ? firstTomb : i;
            if (sh == tombstone && firstTomb < 0)     firstTomb = i;
            i = (i + 1) & mask;
        }
        return firstTomb;
    }

    @Override public void putAll(Map<? extends K, ? extends V> m) { m.forEach(this::put); }

    @Override
    public void clear() {
        long stamp = lock.writeLock();
        try {
            Arrays.fill(hashes,   EMPTY);
            Arrays.fill(keys,     null);
            Arrays.fill(values,   null);
            Arrays.fill(freqs,    0);
            Arrays.fill(lastUsed, 0L);
            liveCount.set(0);
            clock = 0L;
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    @Override public int     size()                   { return liveCount.get(); }
    @Override public boolean isEmpty()                { return liveCount.get() == 0; }
    @Override public boolean containsKey(Object key) { return get(key) != null; }
    @Override public boolean containsValue(Object v) {
        long stamp = lock.readLock();
        try {
            for (int i = 0; i < capacity; i++)
                if (hashes[i] > 0 && v.equals(values[i])) return true;
            return false;
        } finally { lock.unlockRead(stamp); }
    }

    @Override public @NotNull Set<K>          keySet()   { return new KeySetView(); }
    @Override public @NotNull Collection<V>   values()   { return new ValuesView(); }
    @Override public @NotNull Set<Entry<K,V>> entrySet() { return new EntrySetView(); }

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
            int i = next; advance(); return extract(i);
        }
        abstract T extract(int i);
    }

    private final class KeySetView extends AbstractSet<K> {
        @Override public int size() { return liveCount.get(); }
        @Override public boolean contains(Object o) { return containsKey(o); }
        @Override public Iterator<K> iterator() {
            long s = lock.readLock(); try {
                return new SlotIterator<K>() { @Override K extract(int i) { return (K) keys[i]; } };
            } finally { lock.unlockRead(s); }
        }
    }

    private final class ValuesView extends AbstractCollection<V> {
        @Override public int size() { return liveCount.get(); }
        @Override public Iterator<V> iterator() {
            long s = lock.readLock(); try {
                return new SlotIterator<V>() { @Override V extract(int i) { return (V) values[i]; } };
            } finally { lock.unlockRead(s); }
        }
    }

    private final class EntrySetView extends AbstractSet<Entry<K,V>> {
        @Override public int size() { return liveCount.get(); }
        @Override public Iterator<Entry<K,V>> iterator() {
            long s = lock.readLock(); try {
                return new SlotIterator<Entry<K,V>>() {
                    @Override Entry<K,V> extract(int i) {
                        return Map.entry(keys[i], values[i]);
                    }
                };
            } finally { lock.unlockRead(s); }
        }
    }

    public static int nextPow2(int n) {
        if (n <= 1) return 2;
        n--; n |= n>>>1; n |= n>>>2; n |= n>>>4; n |= n>>>8; n |= n>>>16;
        return n + 1;
    }
}