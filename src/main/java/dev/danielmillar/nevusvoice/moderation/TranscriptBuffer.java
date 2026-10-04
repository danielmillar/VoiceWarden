package dev.danielmillar.nevusvoice.moderation;

import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Rolling per-player transcript history (context for alerts, /viewreport, /reportvoice). Optionally keeps recent
 * speech audio under one global memory budget: when exceeded, the oldest audio server-wide is released first.
 * Thread-safe; per-player operations lock only that player's history.
 */
public final class TranscriptBuffer {

    public static final class Entry {
        private final Instant spokenAt;
        private final String text;
        private final AtomicReference<short @Nullable []> audio;

        Entry(Instant spokenAt, String text, short @Nullable [] audio) {
            this.spokenAt = spokenAt;
            this.text = text;
            this.audio = new AtomicReference<>(audio);
        }

        public Instant spokenAt() {
            return spokenAt;
        }

        public String text() {
            return text;
        }

        public short @Nullable [] audio() {
            return audio.get();
        }

        public TranscriptLine line() {
            return new TranscriptLine(spokenAt, text);
        }
    }

    private record History(String name, ArrayDeque<Entry> entries) {
    }

    /** Settings snapshot; changed on reload. */
    public record Limits(Duration retention, int maxLines, long audioBudgetBytes) {
    }

    private final ConcurrentHashMap<UUID, History> histories = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Entry> audioOrder = new ConcurrentLinkedQueue<>();
    private final AtomicLong audioBytes = new AtomicLong();
    private volatile Limits limits;

    public TranscriptBuffer(Limits limits) {
        this.limits = limits;
    }

    public void setLimits(Limits limits) {
        this.limits = limits;
        evictAudio();
    }

    public void add(UUID player, String name, Instant spokenAt, String text, short @Nullable [] audio) {
        Limits l = limits;
        short[] kept = l.audioBudgetBytes() > 0 ? audio : null;
        Entry entry = new Entry(spokenAt, text, kept);
        // compute() makes append+trim atomic with prune()'s removal of empty histories (same key), so an entry can
        // never be appended to a history that was just detached from the map.
        histories.compute(player, (k, h) -> {
            History history = h == null ? new History(name, new ArrayDeque<>())
                    : h.name.equals(name) ? h : new History(name, h.entries);
            synchronized (history.entries) {
                insertInOrder(history.entries, entry);
                while (history.entries.size() > l.maxLines()) {
                    release(history.entries.removeFirst());
                }
            }
            return history;
        });
        if (kept != null) {
            audioOrder.add(entry);
            audioBytes.addAndGet(kept.length * 2L);
            evictAudio();
        }
    }

    /** Workers can finish a player's utterances out of order; keep each history sorted by spoken time. */
    private static void insertInOrder(ArrayDeque<Entry> entries, Entry entry) {
        if (entries.isEmpty() || !entries.peekLast().spokenAt.isAfter(entry.spokenAt)) {
            entries.addLast(entry);
            return;
        }
        ArrayDeque<Entry> later = new ArrayDeque<>();
        while (!entries.isEmpty() && entries.peekLast().spokenAt.isAfter(entry.spokenAt)) {
            later.addFirst(entries.removeLast());
        }
        entries.addLast(entry);
        entries.addAll(later);
    }

    /** The most recent {@code n} lines, oldest first. */
    public List<TranscriptLine> recentLines(UUID player, int n) {
        History history = histories.get(player);
        if (history == null || n <= 0) {
            return List.of();
        }
        synchronized (history.entries) {
            int skip = Math.max(0, history.entries.size() - n);
            List<TranscriptLine> lines = new ArrayList<>(Math.min(n, history.entries.size()));
            Iterator<Entry> it = history.entries.iterator();
            for (int i = 0; it.hasNext(); i++) {
                Entry e = it.next();
                if (i >= skip) {
                    lines.add(e.line());
                }
            }
            return lines;
        }
    }

    /** Entries spoken at or after {@code since}, oldest first. */
    public List<Entry> since(UUID player, Instant since) {
        History history = histories.get(player);
        if (history == null) {
            return List.of();
        }
        synchronized (history.entries) {
            List<Entry> out = new ArrayList<>();
            for (Entry e : history.entries) {
                if (!e.spokenAt.isBefore(since)) {
                    out.add(e);
                }
            }
            return out;
        }
    }

    public Optional<String> lastKnownName(UUID player) {
        History history = histories.get(player);
        return history == null ? Optional.empty() : Optional.of(history.name);
    }

    /** Players with buffered speech (for tab completion), as id → name. */
    public java.util.Map<UUID, String> knownPlayers() {
        java.util.Map<UUID, String> out = new java.util.HashMap<>();
        histories.forEach((id, h) -> out.put(id, h.name));
        return out;
    }

    /** Drops lines older than the retention period. Run periodically off the main thread. */
    public void prune(Instant now) {
        Instant cutoff = now.minus(limits.retention());
        for (UUID player : histories.keySet()) {
            histories.computeIfPresent(player, (k, history) -> {
                synchronized (history.entries) {
                    while (!history.entries.isEmpty() && history.entries.peekFirst().spokenAt.isBefore(cutoff)) {
                        release(history.entries.removeFirst());
                    }
                    return history.entries.isEmpty() ? null : history;
                }
            });
        }
        audioOrder.removeIf(e -> e.audio.get() == null);
    }

    public long audioBytes() {
        return audioBytes.get();
    }

    private void evictAudio() {
        long budget = limits.audioBudgetBytes();
        while (audioBytes.get() > budget) {
            Entry oldest = audioOrder.poll();
            if (oldest == null) {
                break;
            }
            release(oldest);
        }
    }

    private void release(Entry entry) {
        short[] a = entry.audio.getAndSet(null);
        if (a != null) {
            audioBytes.addAndGet(-a.length * 2L);
        }
    }
}
