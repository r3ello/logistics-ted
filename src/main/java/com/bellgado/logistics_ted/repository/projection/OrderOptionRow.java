package com.bellgado.logistics_ted.repository.projection;

import java.time.Instant;
import java.util.UUID;

/**
 * Native-query projection for the alternatives shown on the order detail page. Includes
 * the computed {@code isChosen} flag (id = customer_order.chosen_option_id) so the FE
 * doesn't need to cross-reference.
 */
public interface OrderOptionRow {
    UUID getPublicId();
    String getObjective();
    Boolean getIsPrimary();
    Integer getSequenceIndex();
    Integer getTotalDistanceKm();
    Integer getTotalMinutes();
    Integer getTotalStops();
    Integer getSupplierStopsCount();
    Boolean getFullyFulfilled();
    String getMapsUrl();
    Instant getFirstViewedAt();
    Instant getLastViewedAt();
    Integer getViewCount();
    Boolean getIsChosen();
    String getPayloadJson();
}
