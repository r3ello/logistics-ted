package com.bellgado.logistics_ted.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record OrderResponse(
    LocationDto origin,
    LocationDto destination,
    List<RouteStopDto> route,
    List<RouteStopDto> supplierStops,
    List<DeficitDto> deficit,
    String mapsUrl,
    boolean fullyFulfilled,
    int totalStops,
    long totalDistance,
    long totalMinutes,
    List<RouteOptionDto> alternatives
) {

    public record LocationDto(Object id, String name, String location) {}

    public record RouteStopDto(
        Integer id,
        String name,
        String location,
        Double lat,
        Double lng,
        Map<Integer, StopContributionDto> contribution
    ) {}

    public record StopContributionDto(
        double quantity,
        String name,
        String unit,
        String selectionReason,
        int distanceFromOrigin,
        double availableQty
    ) {}

    public record DeficitDto(double quantity, String name, String unit) {}

    /**
     * One alternative route ranked under a specific objective. Same shape as the top-level
     * fields, but tagged with an {@code objective} label so the frontend can render them as
     * choices. Phase 7 returns up to three options, deduplicated by stop sequence.
     */
    public record RouteOptionDto(
        String objective,
        List<RouteStopDto> route,
        List<RouteStopDto> supplierStops,
        List<DeficitDto> deficit,
        String mapsUrl,
        boolean fullyFulfilled,
        int totalStops,
        long totalDistance,
        long totalMinutes
    ) {}
}
