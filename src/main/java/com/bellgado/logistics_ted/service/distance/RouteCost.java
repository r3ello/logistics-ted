package com.bellgado.logistics_ted.service.distance;

/**
 * Per-pair cost: kilometres and seconds. Phase-7 type that lets the solver optimise for
 * distance, time, or a weighted blend without re-issuing distance calls.
 */
public record RouteCost(double km, double seconds) {

    public static final RouteCost ZERO = new RouteCost(0, 0);

    public double minutes() {
        return seconds / 60.0;
    }
}
