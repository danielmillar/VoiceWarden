package dev.danielmillar.voicesentinel.health;

import java.util.Arrays;
import java.util.concurrent.atomic.LongAdder;

/** Lock-free counters for the whole pipeline. Updated from many threads, read by /nv stats and the health check. */
public final class Metrics {

    public final LongAdder packets = new LongAdder();
    public final LongAdder packetsDropped = new LongAdder();
    public final LongAdder decodeErrors = new LongAdder();
    public final LongAdder utterances = new LongAdder();
    public final LongAdder utterancesTooShort = new LongAdder();
    public final LongAdder utterancesTooQuiet = new LongAdder();
    public final LongAdder queueDropped = new LongAdder();
    public final LongAdder staleDropped = new LongAdder();
    public final LongAdder noSpeech = new LongAdder();
    public final LongAdder transcribed = new LongAdder();
    public final LongAdder transcriptionErrors = new LongAdder();
    public final LongAdder incidents = new LongAdder();
    public final LongAdder autoMutes = new LongAdder();
    public final LongAdder warnings = new LongAdder();
    /** Audio duration that went through the recogniser, in milliseconds. */
    public final LongAdder audioMillis = new LongAdder();
    /** Wall time spent inside the recogniser, in milliseconds. */
    public final LongAdder decodeMillis = new LongAdder();

    private final long[] latencies = new long[512];
    private int latencyCount;
    private int latencyNext;

    /** Records end-of-speech → transcript-available latency. */
    public void recordLatency(long millis) {
        synchronized (latencies) {
            latencies[latencyNext] = millis;
            latencyNext = (latencyNext + 1) % latencies.length;
            latencyCount = Math.min(latencyCount + 1, latencies.length);
        }
    }

    /** @return {average, p95} of the most recent latencies in ms, or {-1, -1} when none were recorded */
    public long[] latencySummary() {
        long[] copy;
        synchronized (latencies) {
            if (latencyCount == 0) {
                return new long[]{-1, -1};
            }
            copy = Arrays.copyOf(latencies, latencyCount);
        }
        Arrays.sort(copy);
        long sum = 0;
        for (long v : copy) {
            sum += v;
        }
        int p95 = Math.min(copy.length - 1, (int) Math.ceil(copy.length * 0.95) - 1);
        return new long[]{sum / copy.length, copy[Math.max(0, p95)]};
    }

    /** Real-time factor inverse: seconds of audio transcribed per second of compute (higher is better). */
    public double speedFactor() {
        long decode = decodeMillis.sum();
        return decode == 0 ? 0 : (double) audioMillis.sum() / decode;
    }
}
