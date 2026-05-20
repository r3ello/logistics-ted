package com.bellgado.logistics_ted.service.distance;

/**
 * Pre-computed pairwise {@link RouteCost} (km + seconds) for a fixed set of points. Phase-2's
 * matrix that the heuristic reads at O(1); phase 7 added the seconds dimension so the solver
 * can optimise for time as well as distance.
 *
 * Treated as potentially asymmetric so phase-4 road costs where {@code km(a,b) != km(b,a)} drop
 * in without changing the consumer.
 */
public final class RouteCostMatrix {

    private final RouteCost[][] cells;

    public RouteCostMatrix(RouteCost[][] cells) {
        this.cells = cells;
    }

    /** Backward-compat: build a km-only matrix (seconds defaulted to 0). */
    public RouteCostMatrix(double[][] km) {
        this.cells = new RouteCost[km.length][];
        for (int i = 0; i < km.length; i++) {
            this.cells[i] = new RouteCost[km[i].length];
            for (int j = 0; j < km[i].length; j++) {
                this.cells[i][j] = new RouteCost(km[i][j], 0);
            }
        }
    }

    public double km(int from, int to) {
        return cells[from][to].km();
    }

    public double seconds(int from, int to) {
        return cells[from][to].seconds();
    }

    public RouteCost cost(int from, int to) {
        return cells[from][to];
    }

    public int size() {
        return cells.length;
    }
}
