package com.bellgado.logistics_ted.service.distance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class CachingRouteMatrixServiceTest {

    @Test
    void firstCallDelegates_secondIdenticalCallServesEverythingFromCache() {
        RouteMatrixService delegate = mock(RouteMatrixService.class);
        var delegateMatrix = matrix(new double[][]{
            {0, 5,  10},
            {5, 0,  15},
            {10, 15, 0}
        });
        when(delegate.compute(any())).thenReturn(delegateMatrix);

        var cache = new RouteCostCache(Duration.ofMinutes(10), 100, Clock.systemUTC());
        var service = new CachingRouteMatrixService(delegate, cache);

        List<double[]> points = List.of(
            new double[]{0.0, 0.0},
            new double[]{1.0, 1.0},
            new double[]{2.0, 2.0}
        );

        RouteCostMatrix first = service.compute(points);
        assertMatrixEquals(first, delegateMatrix);
        verify(delegate, times(1)).compute(any());

        RouteCostMatrix second = service.compute(points);
        assertMatrixEquals(second, delegateMatrix);
        verify(delegate, times(1)).compute(any());
    }

    @Test
    void overlappingRequestStillDelegatesOnceForMissedPairs() {
        RouteMatrixService delegate = mock(RouteMatrixService.class);
        var firstMatrix = matrix(new double[][]{
            {0,  5,  10},
            {5,  0,  15},
            {10, 15, 0}
        });
        // secondMatrix[0][1] = 99 differs from the cached 5 so the assert below proves the cache
        // wins for hit pairs even when the delegate is invoked for the miss pairs.
        var secondMatrix = matrix(new double[][]{
            {0,  99, 20},
            {99, 0,  25},
            {20, 25, 0}
        });
        when(delegate.compute(any())).thenReturn(firstMatrix, secondMatrix);

        var cache = new RouteCostCache(Duration.ofMinutes(10), 100, Clock.systemUTC());
        var service = new CachingRouteMatrixService(delegate, cache);

        service.compute(List.of(
            new double[]{0.0, 0.0},
            new double[]{1.0, 1.0},
            new double[]{2.0, 2.0}
        ));

        RouteCostMatrix overlap = service.compute(List.of(
            new double[]{0.0, 0.0},
            new double[]{1.0, 1.0},
            new double[]{9.9, 9.9}
        ));

        verify(delegate, times(2)).compute(any());
        assertThat(overlap.km(0, 1)).isEqualTo(5.0);
        assertThat(overlap.km(1, 0)).isEqualTo(5.0);
        assertThat(overlap.km(0, 2)).isEqualTo(20.0);
        assertThat(overlap.km(2, 0)).isEqualTo(20.0);
        assertThat(overlap.km(1, 2)).isEqualTo(25.0);
        assertThat(overlap.km(2, 1)).isEqualTo(25.0);
    }

    @Test
    void expiredCacheEntriesForceDelegateCallAgain() {
        RouteMatrixService delegate = mock(RouteMatrixService.class);
        var freshMatrix = matrix(new double[][]{
            {0, 5,  10},
            {5, 0,  15},
            {10, 15, 0}
        });
        when(delegate.compute(any())).thenReturn(freshMatrix);

        var clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var cache = new RouteCostCache(Duration.ofMinutes(1), 100, clock);
        var service = new CachingRouteMatrixService(delegate, cache);

        List<double[]> points = List.of(
            new double[]{0.0, 0.0},
            new double[]{1.0, 1.0},
            new double[]{2.0, 2.0}
        );

        service.compute(points);
        clock.advance(Duration.ofMinutes(2));
        service.compute(points);

        verify(delegate, times(2)).compute(any());
    }

    @Test
    void allCachedMeansDelegateNeverCalled() {
        RouteMatrixService delegate = mock(RouteMatrixService.class);
        var cache = new RouteCostCache(Duration.ofMinutes(10), 100, Clock.systemUTC());
        cache.put(0.0, 0.0, 1.0, 1.0, new RouteCost(5.0, 300));
        cache.put(1.0, 1.0, 0.0, 0.0, new RouteCost(5.0, 300));

        var service = new CachingRouteMatrixService(delegate, cache);
        RouteCostMatrix result = service.compute(List.of(
            new double[]{0.0, 0.0},
            new double[]{1.0, 1.0}
        ));

        verify(delegate, never()).compute(any());
        assertThat(result.km(0, 1)).isEqualTo(5.0);
        assertThat(result.km(1, 0)).isEqualTo(5.0);
    }

    private static RouteCostMatrix matrix(double[][] km) {
        return new RouteCostMatrix(km);
    }

    private static void assertMatrixEquals(RouteCostMatrix actual, RouteCostMatrix expected) {
        assertThat(actual.size()).isEqualTo(expected.size());
        for (int i = 0; i < expected.size(); i++) {
            for (int j = 0; j < expected.size(); j++) {
                assertThat(actual.km(i, j))
                    .as("cell [%d][%d]", i, j)
                    .isEqualTo(expected.km(i, j));
            }
        }
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
