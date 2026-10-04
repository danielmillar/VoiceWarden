package dev.danielmillar.nevusvoice.audio;

import java.util.Arrays;

/** Growable 16-bit PCM buffer. Not thread-safe. */
public final class PcmBuffer {

    private final int initialCapacity;
    private short[] data;
    private int size;

    public PcmBuffer(int initialCapacity) {
        this.initialCapacity = initialCapacity;
        this.data = new short[initialCapacity];
    }

    public void append(short sample) {
        if (size == data.length) {
            ensureCapacity(size + 1);
        }
        data[size++] = sample;
    }

    public void ensureCapacity(int capacity) {
        if (capacity > data.length) {
            data = Arrays.copyOf(data, Math.max(capacity, data.length + (data.length >> 1)));
        }
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    /** Direct access to the backing array; valid indices are {@code [0, size())}. */
    public short[] array() {
        return data;
    }

    public short[] copy(int from, int to) {
        return Arrays.copyOfRange(data, from, to);
    }

    /** Drops samples before {@code from}, keeping {@code [from, size)} at the start of the buffer. */
    public void discardBefore(int from) {
        System.arraycopy(data, from, data, 0, size - from);
        size -= from;
    }

    public void clear() {
        size = 0;
    }

    /** Clears and releases memory grown beyond the initial capacity (after long utterances). */
    public void clearAndTrim() {
        size = 0;
        if (data.length > initialCapacity) {
            data = new short[initialCapacity];
        }
    }
}
