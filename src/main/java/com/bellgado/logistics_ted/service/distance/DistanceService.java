package com.bellgado.logistics_ted.service.distance;

/**
 * Pairwise cost lookup. Phase-7: returns {@link RouteCost} (km + seconds) so the solver can
 * optimise for distance, time, or a weighted blend. The legacy {@link #km(double, double,
 * double, double)} default method preserves callers that only care about kilometres.
 */
public interface DistanceService {

    RouteCost cost(double lat1, double lng1, double lat2, double lng2);

    default double km(double lat1, double lng1, double lat2, double lng2) {
        return cost(lat1, lng1, lat2, lng2).km();
    }

    default double seconds(double lat1, double lng1, double lat2, double lng2) {
        return cost(lat1, lng1, lat2, lng2).seconds();
    }
}
