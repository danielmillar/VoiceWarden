package dev.voicewarden.core.audio;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.LongAdder;

public final class OpusFramePool {

    private final ArrayBlockingQueue<PooledOpusFrame> free;
    private final int capacity;
    private final int maxOpusBytes;
    private final LongAdder exhausted = new LongAdder();

    public OpusFramePool(int capacity, int maxOpusBytes) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Pool capacity must be positive, got " + capacity);
        }
        if (maxOpusBytes <= 0) {
            throw new IllegalArgumentException("Max Opus frame size must be positive, got " + maxOpusBytes);
        }
        this.capacity = capacity;
        this.maxOpusBytes = maxOpusBytes;
        this.free = new ArrayBlockingQueue<>(capacity);
        for (int i = 0; i < capacity; i++) {
            free.offer(new PooledOpusFrame(maxOpusBytes));
        }
    }

    public PooledOpusFrame acquire() {
        PooledOpusFrame frame = free.poll();
        if (frame == null) {
            exhausted.increment();
        }
        return frame;
    }

    public void release(PooledOpusFrame frame) {
        if (frame == null) {
            return;
        }
        frame.reset();
        free.offer(frame);
    }

    public int available() {
        return free.size();
    }

    public int capacity() {
        return capacity;
    }

    public int maxOpusBytes() {
        return maxOpusBytes;
    }

    public long exhaustedCount() {
        return exhausted.sum();
    }
}
