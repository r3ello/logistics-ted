package com.bellgado.logistics_ted.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Routing layer config. {@code provider=haversine} keeps the previous in-process haversine path;
 * {@code provider=google} swaps the delegate to {@code GoogleRoutesMatrixService}, which falls
 * back to haversine on any API error or incomplete response.
 *
 * Phase-7 additions:
 *  - {@code haversine.speed-kmh} — average driving speed used to estimate seconds when Google
 *    is off. Keeps the "fastest time" objective working in fallback mode.
 *  - {@code balanced.alpha} / {@code balanced.beta} — weights for the balanced objective:
 *    {@code cost = alpha * km + beta * minutes}.
 */
@ConfigurationProperties("routing")
public record RoutingProperties(
    @DefaultValue("haversine") String provider,
    @DefaultValue Google google,
    @DefaultValue Haversine haversine,
    @DefaultValue Balanced balanced,
    @DefaultValue Cache cache,
    @DefaultValue Fuel fuel
) {

    public record Google(
        @DefaultValue("") String apiKey,
        @DefaultValue("https://routes.googleapis.com") String baseUrl,
        @DefaultValue("DRIVE") String travelMode,
        @DefaultValue("TRAFFIC_AWARE") String routingPreference,
        @DefaultValue("5") int requestTimeoutSeconds
    ) {}

    public record Haversine(
        @DefaultValue("40") double speedKmh
    ) {}

    public record Balanced(
        @DefaultValue("1.0") double alpha,
        @DefaultValue("1.0") double beta
    ) {}

    public record Cache(
        @DefaultValue("86400") int ttlSeconds,
        @DefaultValue("50000") int maxSize
    ) {}

    public record Fuel(
        @DefaultValue("8.0") double consumptionLPer100km,
        @DefaultValue("1.80") double pricePerLitreEur
    ) {}
}
