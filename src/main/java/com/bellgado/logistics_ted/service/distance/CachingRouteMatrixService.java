package com.bellgado.logistics_ted.service.distance;

import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decorator that serves {@link RouteCostMatrix} cells from {@link RouteCostCache} when available
 * and only delegates to the underlying {@link RouteMatrixService} when at least one pair misses.
 *
 * Phase-3 implementation, phase-7 widened from km to full {@link RouteCost}: when any cell is
 * missing the decorator asks the delegate for the full matrix and harvests only the missed
 * pairs (cached pairs win even if the delegate returns different values for them).
 */
public class CachingRouteMatrixService implements RouteMatrixService {

    private static final Logger log = LoggerFactory.getLogger(CachingRouteMatrixService.class);

    private final RouteMatrixService delegate;
    private final RouteCostCache cache;

    public CachingRouteMatrixService(RouteMatrixService delegate, RouteCostCache cache) {
        this.delegate = delegate;
        this.cache = cache;
    }

    @Override
    public RouteCostMatrix compute(List<double[]> points) {
        int n = points.size();
        RouteCost[][] cells = new RouteCost[n][n];
        boolean[][] missing = new boolean[n][n];
        boolean anyMiss = false;
        int hits = 0;
        int misses = 0;

        for (int i = 0; i < n; i++) {
            double[] a = points.get(i);
            for (int j = 0; j < n; j++) {
                if (i == j) {
                    cells[i][j] = RouteCost.ZERO;
                    continue;
                }
                double[] b = points.get(j);
                Optional<RouteCost> cached = cache.get(a[0], a[1], b[0], b[1]);
                if (cached.isPresent()) {
                    cells[i][j] = cached.get();
                    hits++;
                } else {
                    missing[i][j] = true;
                    anyMiss = true;
                    misses++;
                }
            }
        }

        int offDiagonal = n * (n - 1);
        log.info("cache: {}/{} pair(s) hit, {} miss(es){}",
            hits, offDiagonal, misses, anyMiss ? " — delegating for fresh values" : " — full cache hit, no delegate call");

        if (anyMiss) {
            RouteCostMatrix fresh = delegate.compute(points);
            for (int i = 0; i < n; i++) {
                double[] a = points.get(i);
                for (int j = 0; j < n; j++) {
                    if (!missing[i][j]) continue;
                    double[] b = points.get(j);
                    RouteCost c = fresh.cost(i, j);
                    cells[i][j] = c;
                    cache.put(a[0], a[1], b[0], b[1], c);
                }
            }
        }

        return new RouteCostMatrix(cells);
    }
}
