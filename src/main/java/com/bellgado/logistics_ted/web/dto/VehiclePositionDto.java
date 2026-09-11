package com.bellgado.logistics_ted.web.dto;

import java.time.Instant;

/** One breadcrumb point of a vehicle's track. */
public record VehiclePositionDto(Instant ts, double lat, double lng, Double speedKmh, Integer heading, Boolean ignition) {}
