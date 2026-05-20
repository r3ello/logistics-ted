package com.bellgado.logistics_ted.service.distance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class RouteCostCacheTest {

    @Test
    void missingKeyReturnsEmpty() {
        var cache = new RouteCostCache(Duration.ofMinutes(1), 100, Clock.systemUTC());
        assertThat(cache.get(1.0, 2.0, 3.0, 4.0)).isEmpty();
    }

    @Test
    void putThenGetReturnsValue() {
        var cache = new RouteCostCache(Duration.ofMinutes(1), 100, Clock.systemUTC());
        cache.put(1.0, 2.0, 3.0, 4.0, new RouteCost(42.5, 1800));
        assertThat(cache.get(1.0, 2.0, 3.0, 4.0)).hasValue(new RouteCost(42.5, 1800));
    }

    @Test
    void cacheIsDirectional() {
        var cache = new RouteCostCache(Duration.ofMinutes(1), 100, Clock.systemUTC());
        cache.put(1.0, 2.0, 3.0, 4.0, new RouteCost(10.0, 600));
        assertThat(cache.get(3.0, 4.0, 1.0, 2.0)).isEmpty();
    }

    @Test
    void coordsRoundedToFiveDecimalsShareEntry() {
        var cache = new RouteCostCache(Duration.ofMinutes(1), 100, Clock.systemUTC());
        cache.put(1.123456, 2.0, 3.0, 4.0, new RouteCost(99.0, 7200));
        assertThat(cache.get(1.123459, 2.0, 3.0, 4.0)).hasValue(new RouteCost(99.0, 7200));
    }

    @Test
    void expiredEntryReturnsEmptyAndIsEvicted() {
        var clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var cache = new RouteCostCache(Duration.ofSeconds(60), 100, clock);

        cache.put(1.0, 2.0, 3.0, 4.0, new RouteCost(7.0, 300));
        assertThat(cache.get(1.0, 2.0, 3.0, 4.0)).hasValue(new RouteCost(7.0, 300));

        clock.advance(Duration.ofSeconds(60));
        assertThat(cache.get(1.0, 2.0, 3.0, 4.0)).isEmpty();
        assertThat(cache.size()).isZero();
    }

    @Test
    void entryStillFreshJustBeforeTtlExpires() {
        var clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var cache = new RouteCostCache(Duration.ofSeconds(60), 100, clock);
        cache.put(1.0, 2.0, 3.0, 4.0, new RouteCost(7.0, 0));

        clock.advance(Duration.ofMillis(59_999));
        assertThat(cache.get(1.0, 2.0, 3.0, 4.0)).hasValue(new RouteCost(7.0, 0));
    }

    @Test
    void exceedingMaxSizeTriggersEviction() {
        var clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var cache = new RouteCostCache(Duration.ofMinutes(10), 4, clock);

        for (int i = 0; i < 4; i++) {
            cache.put(i, 0, 0, 0, new RouteCost(i * 10.0, i * 60));
            clock.advance(Duration.ofMillis(10));
        }
        assertThat(cache.size()).isEqualTo(4);

        cache.put(99, 0, 0, 0, new RouteCost(999.0, 6000));
        assertThat(cache.size()).isLessThanOrEqualTo(4);
        assertThat(cache.get(99, 0, 0, 0)).hasValue(new RouteCost(999.0, 6000));
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant initial) { this.now = initial; }
        void advance(Duration d) { this.now = this.now.plus(d); }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        @Override public long millis() { return now.toEpochMilli(); }
    }
}
