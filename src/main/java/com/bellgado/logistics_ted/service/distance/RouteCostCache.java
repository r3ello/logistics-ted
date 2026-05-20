package com.bellgado.logistics_ted.service.distance;

import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Directional pair-level cache of {@link RouteCost} (km + seconds). Keys are rounded to 5
 * decimal places (≈1 m). Directional because phase-4 road costs are asymmetric:
 * {@code cost(a→b) != cost(b→a)}.
 *
 * Hand-rolled around {@link ConcurrentHashMap} to avoid pulling in Caffeine (SB 3.5.x pin per
 * the project's env-gotchas note). {@link Clock} is injected so tests can advance time without
 * sleeping.
 */
public final class RouteCostCache {

    private final ConcurrentHashMap<PairKey, TimedValue> store = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final int maxSize;
    private final Clock clock;

    public RouteCostCache(Duration ttl, int maxSize, Clock clock) {
        this.ttl = ttl;
        this.maxSize = maxSize;
        this.clock = clock;
    }

    public Optional<RouteCost> get(double fromLat, double fromLng, double toLat, double toLng) {
        PairKey key = key(fromLat, fromLng, toLat, toLng);
        TimedValue v = store.get(key);
        if (v == null) return Optional.empty();
        if (clock.millis() - v.storedAt >= ttl.toMillis()) {
            store.remove(key, v);
            return Optional.empty();
        }
        return Optional.of(v.cost);
    }

    public void put(double fromLat, double fromLng, double toLat, double toLng, RouteCost cost) {
        if (store.size() >= maxSize) evictOldestHalf();
        store.put(key(fromLat, fromLng, toLat, toLng), new TimedValue(cost, clock.millis()));
    }

    public int size() {
        return store.size();
    }

    private static PairKey key(double fromLat, double fromLng, double toLat, double toLng) {
        return new PairKey(round(fromLat), round(fromLng), round(toLat), round(toLng));
    }

    private static double round(double v) {
        return Math.round(v * 100_000.0) / 100_000.0;
    }

    private void evictOldestHalf() {
        store.entrySet().stream()
            .sorted(Comparator.comparingLong(e -> e.getValue().storedAt))
            .limit(Math.max(1, maxSize / 2))
            .map(Map.Entry::getKey)
            .forEach(store::remove);
    }

    private record PairKey(double fromLat, double fromLng, double toLat, double toLng) {}

    private record TimedValue(RouteCost cost, long storedAt) {}
}
