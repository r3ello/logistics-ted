package com.bellgado.logistics_ted.web.dto;

import java.time.Instant;
import java.util.Map;

/** A fleet vehicle: our own fields plus the last GetStatus snapshot. */
public record VehicleDto(
    Integer id,
    String gpsCode,
    /** The alias if set, else the GPS.bg name. */
    String name,
    String providerName,
    String alias,
    String regNumber,
    String notes,
    boolean active,
    Integer driverWorkerId,
    String driverWorkerName,
    Instant lastTs,
    Double lastLat,
    Double lastLng,
    Double speedKmh,
    Integer heading,
    Boolean ignition,
    Boolean online,
    /** The driver GPS.bg identified (tachograph / key), usually null. */
    String providerDriver,
    String address,
    Double odometerKm,
    Double batteryV,
    Double extPowerV,
    Instant statusSyncedAt,
    Instant routesSyncedAt,
    /** Every GetStatus field; only on the single-vehicle endpoints, null in the list. */
    Map<String, Object> telemetry
) {}
