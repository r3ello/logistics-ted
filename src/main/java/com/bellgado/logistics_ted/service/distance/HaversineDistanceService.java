package com.bellgado.logistics_ted.service.distance;

import com.bellgado.logistics_ted.config.RoutingProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Great-circle distance via the haversine formula, plus a time estimate built from a configured
 * average speed so the "fastest time" objective still picks usable routes when Google is off.
 */
@Component
public class HaversineDistanceService implements DistanceService {

    private static final double EARTH_RADIUS_KM = 6371.0;
    private static final double DEFAULT_SPEED_KMH = 40.0;

    private final double assumedSpeedKmh;

    public HaversineDistanceService() {
        this(DEFAULT_SPEED_KMH);
    }

    @Autowired
    public HaversineDistanceService(RoutingProperties props) {
        this(props.haversine().speedKmh());
    }

    private HaversineDistanceService(double assumedSpeedKmh) {
        this.assumedSpeedKmh = assumedSpeedKmh;
    }

    @Override
    public RouteCost cost(double lat1, double lng1, double lat2, double lng2) {
        double km = haversineKm(lat1, lng1, lat2, lng2);
        double seconds = assumedSpeedKmh <= 0 ? 0 : km / assumedSpeedKmh * 3600.0;
        return new RouteCost(km, seconds);
    }

    private static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
            * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
