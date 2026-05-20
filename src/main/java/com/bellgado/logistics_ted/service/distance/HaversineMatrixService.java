package com.bellgado.logistics_ted.service.distance;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the route cost matrix by calling the haversine {@link DistanceService} for every pair.
 * The matrix is symmetric here (and seconds are estimated from km via the configured speed);
 * the phase-4 Google Routes impl is asymmetric and uses real durations.
 *
 * Not annotated — {@code RoutingConfig} wires this behind the caching decorator under a qualifier.
 */
public class HaversineMatrixService implements RouteMatrixService {

    private static final Logger log = LoggerFactory.getLogger(HaversineMatrixService.class);

    private final DistanceService distance;

    public HaversineMatrixService(DistanceService distance) {
        this.distance = distance;
    }

    @Override
    public RouteCostMatrix compute(List<double[]> points) {
        int n = points.size();
        log.info("haversine: building {}x{} symmetric matrix (seconds estimated from configured speed-kmh)", n, n);
        RouteCost[][] cells = new RouteCost[n][n];
        for (int i = 0; i < n; i++) {
            cells[i][i] = RouteCost.ZERO;
            double[] a = points.get(i);
            for (int j = i + 1; j < n; j++) {
                double[] b = points.get(j);
                RouteCost c = distance.cost(a[0], a[1], b[0], b[1]);
                cells[i][j] = c;
                cells[j][i] = c;
            }
        }
        return new RouteCostMatrix(cells);
    }
}
