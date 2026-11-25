package dev.voicewarden.plugin.pipeline;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

final class ExpiringCache<K, V> {

    private record Entry<V>(V value, long expiresAtMillis) {
    }

    private final Map<K, Entry<V>> entries = new LinkedHashMap<>();
    private final Duration ttl;
    private final int maxEntries;
    private final Clock clock;

    ExpiringCache(Duration ttl, int maxEntries, Clock clock) {
        if (!ttl.isPositive() || maxEntries < 1) {
            throw new IllegalArgumentException("ttl and maxEntries must be positive");
        }
        this.ttl = ttl;
        this.maxEntries = maxEntries;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    synchronized V get(K key) {
        Entry<V> entry = entries.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.expiresAtMillis() <= clock.millis()) {
            entries.remove(key);
            return null;
        }
        return entry.value();
    }

    synchronized void put(K key, V value) {
        entries.remove(key);
        entries.put(key, new Entry<>(value, clock.millis() + ttl.toMillis()));
        trim();
    }

    synchronized V putIfAbsent(K key, V value) {
        V existing = get(key);
        if (existing != null) {
            return existing;
        }
        put(key, value);
        return null;
    }

    synchronized V update(K key, Function<K, V> create, UnaryOperator<V> change) {
        V current = get(key);
        V next = change.apply(current == null ? create.apply(key) : current);
        put(key, next);
        return next;
    }

    synchronized V remove(K key) {
        Entry<V> removed = entries.remove(key);
        return removed == null ? null : removed.value();
    }

    synchronized void removeKeys(Predicate<K> filter) {
        entries.keySet().removeIf(filter);
    }

    synchronized List<Map.Entry<K, V>> removeExpired() {
        long now = clock.millis();
        List<Map.Entry<K, V>> expired = new ArrayList<>();
        Iterator<Map.Entry<K, Entry<V>>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<K, Entry<V>> entry = iterator.next();
            if (entry.getValue().expiresAtMillis() <= now) {
                expired.add(Map.entry(entry.getKey(), entry.getValue().value()));
                iterator.remove();
            }
        }
        return expired;
    }

    synchronized List<Map.Entry<K, V>> drain() {
        List<Map.Entry<K, V>> all = new ArrayList<>();
        entries.forEach((key, entry) -> all.add(Map.entry(key, entry.value())));
        entries.clear();
        return all;
    }

    synchronized boolean containsKey(K key) {
        return get(key) != null;
    }

    synchronized int size() {
        return entries.size();
    }

    private void trim() {
        Iterator<K> oldest = entries.keySet().iterator();
        while (entries.size() > maxEntries && oldest.hasNext()) {
            oldest.next();
            oldest.remove();
        }
    }
}
