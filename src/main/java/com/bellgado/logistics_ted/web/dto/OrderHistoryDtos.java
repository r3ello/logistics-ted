package com.bellgado.logistics_ted.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-side DTOs for /api/orders and /api/orders/{id}. These are built directly from
 * native-query projections in OrderHistoryService — no entity-to-DTO factories live here
 * because traversing lazy associations after the service transaction closes throws
 * LazyInitializationException (open-in-view is disabled).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class OrderHistoryDtos {

    private OrderHistoryDtos() {}

    public record ChooseRequest(String objective) {}

    public record OrderSummaryDto(
        UUID orderId,
        Instant createdAt,
        String source,
        String username,
        Long telegramChatId,
        BigDecimal startLat,
        BigDecimal startLng,
        String startName,
        Integer destinationHouseId,
        String destinationHouseName,
        Map<String, Object> materials,
        int alternativesCount,
        boolean fullyFulfilled,
        String chosenObjective,
        Instant chosenAt
    ) {}

    public record OrderOptionDto(
        UUID optionId,
        String objective,
        boolean isPrimary,
        int sequenceIndex,
        int totalDistanceKm,
        int totalMinutes,
        int totalStops,
        int supplierStopsCount,
        boolean fullyFulfilled,
        String mapsUrl,
        Instant firstViewedAt,
        Instant lastViewedAt,
        int viewCount,
        boolean isChosen,
        Map<String, Object> payload
    ) {}

    public record OrderEventDto(
        String eventType,
        Instant at,
        String objective,
        String username,
        Map<String, Object> metadata
    ) {}

    public record OrderDetailDto(
        OrderSummaryDto summary,
        List<OrderOptionDto> options,
        List<OrderEventDto> events
    ) {}
}
