package com.bellgado.logistics_ted.gps;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One vehicle as GetStatus reports it right now. {@code raw} keeps every provider field (analog
 * inputs, digital I/O, tachograph...) under GPS.bg's own names — note {@code AnamaxVal1}, lowercase m.
 */
public record VehicleStatus(
    String code,
    String name,
    String regNumber,
    String address,
    Boolean ignition,
    /** GPS.bg IsOnline — reports true for a unit silent for days; don't rely on it alone. */
    Boolean online,
    Instant lastTs,
    Double lat,
    Double lng,
    Double speedKmh,
    Integer heading,
    /** Null when GPS.bg has no identified driver (it sends the placeholder "Неизвестен"). */
    String driver,
    Double odometerKm,
    Double batteryV,
    Double extPowerV,
    Map<String, String> raw
) {

    static VehicleStatus from(Map<String, String> r, ZoneId zone) {
        Double lat = GpsValues.dbl(r.get("LastLat"));
        Double lng = GpsValues.dbl(r.get("LastLon"));
        if (lat != null && lng != null && lat == 0 && lng == 0) {
            lat = null;   // no fix yet, not a truck in the Gulf of Guinea
            lng = null;
        }
        return new VehicleStatus(
            GpsValues.text(r.get("Code")),
            GpsValues.text(r.get("Name")),
            GpsValues.text(r.get("CarRegNum")),
            GpsValues.text(r.get("Address")),
            GpsValues.bool(r.get("Ign")),
            GpsValues.bool(r.get("IsOnline")),
            GpsValues.ts(r.get("LastTS"), zone),
            lat,
            lng,
            GpsValues.dbl(r.get("LastSpeed")),
            GpsValues.integer(r.get("LastHeading")),
            GpsValues.text(r.get("LastDriver")),
            GpsValues.dbl(r.get("ObjectDistance")),
            GpsValues.dbl(r.get("LastBatLevel")),
            GpsValues.dbl(r.get("LastExtPowerLevel")),
            Collections.unmodifiableMap(new LinkedHashMap<>(r)));
    }
}
