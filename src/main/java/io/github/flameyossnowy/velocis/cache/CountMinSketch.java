package io.github.flameyossnowy.velocis.cache;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Count-Min Sketch for frequency estimation.
 */
public class CountMinSketch<K> {
    private static final int WIDTH = 128;  // Number of columns
    private static final int DEPTH = 4;    // Number of hash functions
    private final int[][] table;
    private final int[] hashSeeds;

    public CountMinSketch() {
        this.table = new int[DEPTH][WIDTH];
        this.hashSeeds = new int[DEPTH];
        for (int i = 0; i < DEPTH; i++) {
            hashSeeds[i] = ThreadLocalRandom.current().nextInt();
        }
    }

    public void increment(K key) {
        int hash = key.hashCode();
        for (int i = 0; i < DEPTH; i++) {
            int index = Math.abs((hash ^ hashSeeds[i]) % WIDTH); // Ensure positive index
            table[i][index]++;
        }
    }

    public int getFrequency(K key) {
        int hash = key.hashCode();
        int min = Integer.MAX_VALUE;
        for (int i = 0; i < DEPTH; i++) {
            int index = Math.abs((hash ^ hashSeeds[i]) % WIDTH); // Ensure positive index
            min = Math.min(min, table[i][index]);
        }
        return min;
    }

    public void clear() {
        for (int i = 0; i < DEPTH; i++) {
            for (int j = 0; j < WIDTH; j++) {
                table[i][j] = 0;
            }
        }
    }
}
