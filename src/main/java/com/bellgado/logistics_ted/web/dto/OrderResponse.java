package com.bellgado.logistics_ted.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record OrderResponse(
    UUID orderId,
    LocationDto origin,
    LocationDto destination,
    List<RouteStopDto> route,
    List<RouteStopDto> warehouseStops,
    List<RouteStopDto> supplierStops,
    List<DeficitDto> deficit,
    String mapsUrl,
    boolean fullyFulfilled,
    int totalStops,
    long totalDistance,
    long totalMinutes,
    List<RouteOptionDto> alternatives
) {

    /**
     * Legacy convenience constructor (no orderId, no warehouseStops) — kept so existing
     * callers/tests compile unchanged; warehouseStops defaults to empty.
     */
    public OrderResponse(LocationDto origin, LocationDto destination,
                         List<RouteStopDto> route, List<RouteStopDto> supplierStops,
                         List<DeficitDto> deficit, String mapsUrl, boolean fullyFulfilled,
                         int totalStops, long totalDistance, long totalMinutes,
                         List<RouteOptionDto> alternatives) {
        this(null, origin, destination, route, List.of(), supplierStops, deficit, mapsUrl,
            fullyFulfilled, totalStops, totalDistance, totalMinutes, alternatives);
    }

    /** Convenience constructor (no orderId) that carries warehouseStops. */
    public OrderResponse(LocationDto origin, LocationDto destination,
                         List<RouteStopDto> route, List<RouteStopDto> warehouseStops,
                         List<RouteStopDto> supplierStops, List<DeficitDto> deficit,
                         String mapsUrl, boolean fullyFulfilled, int totalStops,
                         long totalDistance, long totalMinutes,
                         List<RouteOptionDto> alternatives) {
        this(null, origin, destination, route, warehouseStops, supplierStops, deficit, mapsUrl,
            fullyFulfilled, totalStops, totalDistance, totalMinutes, alternatives);
    }

    public OrderResponse withIds(UUID orderId, List<RouteOptionDto> withOptionIds) {
        return new OrderResponse(orderId, origin, destination, route, warehouseStops, supplierStops,
            deficit, mapsUrl, fullyFulfilled, totalStops, totalDistance, totalMinutes, withOptionIds);
    }

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
     * One alternative route ranked under a specific objective. {@code optionId} is filled
     * in by the persistence layer so the frontend can post follow-up view/choose events;
     * it stays {@code null} for paths that bypass persistence (tests, in-process tools).
     *
     * <p>Stops are split by tier: {@code route} = houses (tier 1), {@code warehouseStops} =
     * company depots (tier 2), {@code supplierStops} = external suppliers (tier 3). The full
     * pickup order is route &rarr; warehouseStops &rarr; supplierStops.
     */
    public record RouteOptionDto(
        UUID optionId,
        String objective,
        List<RouteStopDto> route,
        List<RouteStopDto> warehouseStops,
        List<RouteStopDto> supplierStops,
        List<DeficitDto> deficit,
        String mapsUrl,
        boolean fullyFulfilled,
        int totalStops,
        long totalDistance,
        long totalMinutes
    ) {

        /**
         * Legacy convenience constructor (no optionId, no warehouseStops) — kept so existing
         * callers/tests compile unchanged; warehouseStops defaults to empty.
         */
        public RouteOptionDto(String objective, List<RouteStopDto> route,
                              List<RouteStopDto> supplierStops, List<DeficitDto> deficit,
                              String mapsUrl, boolean fullyFulfilled, int totalStops,
                              long totalDistance, long totalMinutes) {
            this(null, objective, route, List.of(), supplierStops, deficit, mapsUrl,
                fullyFulfilled, totalStops, totalDistance, totalMinutes);
        }

        /** Convenience constructor (no optionId) that carries warehouseStops. */
        public RouteOptionDto(String objective, List<RouteStopDto> route,
                              List<RouteStopDto> warehouseStops, List<RouteStopDto> supplierStops,
                              List<DeficitDto> deficit, String mapsUrl, boolean fullyFulfilled,
                              int totalStops, long totalDistance, long totalMinutes) {
            this(null, objective, route, warehouseStops, supplierStops, deficit, mapsUrl,
                fullyFulfilled, totalStops, totalDistance, totalMinutes);
        }

        public RouteOptionDto withOptionId(UUID id) {
            return new RouteOptionDto(id, objective, route, warehouseStops, supplierStops, deficit,
                mapsUrl, fullyFulfilled, totalStops, totalDistance, totalMinutes);
        }
    }
}
