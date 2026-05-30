package com.bellgado.logistics_ted.repository.projection;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Native-query projection for the order list and order detail header. Carries exactly the
 * columns needed to build {@code OrderSummaryDto}, plus {@code appUserId} which is used by
 * the service to enforce owner-only access (never leaked to clients).
 */
public interface OrderSummaryRow {
    UUID getPublicId();
    Instant getCreatedAt();
    String getSource();
    String getUsername();
    Long getTelegramChatId();
    BigDecimal getStartLat();
    BigDecimal getStartLng();
    String getStartName();
    Integer getDestinationHouseId();
    String getDestinationHouseName();
    String getMaterialsJson();
    Integer getAppUserId();
    Integer getAlternativesCount();
    Boolean getFullyFulfilled();
    String getChosenObjective();
    Instant getChosenAt();
}
