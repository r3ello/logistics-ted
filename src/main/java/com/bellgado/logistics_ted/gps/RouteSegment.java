package com.bellgado.logistics_ted.gps;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One VehicleRoutesList row: a driving segment ({@code moving}, GPS.bg {@code Ign=1}) or a stop.
 * The real response carries more fields than GPS.bg's PHP sample documents; all of them stay in
 * {@code raw}.
 */
public record RouteSegment(
    boolean moving,
    Instant start,
    Instant end,
    Integer durationS,
    Double startLat,
    Double startLng,
    Double endLat,
    Double endLng,
    Double distanceKm,
    Double avgSpeedKmh,
    Double maxSpeedKmh,
    Double maxAccel,
    Double maxDecel,
    /** From {@code Idle_time}; the {@code Idle} field is the same thing as a "25%" string. */
    Integer idleS,
    /** {@code Fuel_1} = distance x a flat 8 L/100 km. An estimate. */
    Double fuelEstL,
    String driver,
    String startPlace,
    String endPlace,
    Map<String, String> raw
) {

    static RouteSegment from(Map<String, String> r, ZoneId zone) {
        return new RouteSegment(
            Boolean.TRUE.equals(GpsValues.bool(r.get("Ign"))),
            GpsValues.ts(r.get("sTS"), zone),
            GpsValues.ts(r.get("eTS"), zone),
            GpsValues.integer(r.get("Interval")),
            GpsValues.dbl(r.get("sLat")),
            GpsValues.dbl(r.get("sLon")),
            GpsValues.dbl(r.get("eLat")),
            GpsValues.dbl(r.get("eLon")),
            GpsValues.dbl(r.get("Distance")),
            GpsValues.dbl(r.get("AvgSpeed")),
            GpsValues.dbl(r.get("MaxSpeed")),
            GpsValues.dbl(r.get("MaxAccel")),
            GpsValues.dbl(r.get("MaxDecel")),
            GpsValues.integer(r.get("Idle_time")),
            GpsValues.dbl(r.get("Fuel_1")),
            GpsValues.text(r.get("Driver")),
            GpsValues.text(r.get("sPlace")),
            GpsValues.text(r.get("ePlace")),
            Collections.unmodifiableMap(new LinkedHashMap<>(r)));
    }
}
