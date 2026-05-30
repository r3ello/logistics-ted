package com.bellgado.logistics_ted.repository.projection;

import java.time.Instant;

/**
 * Native-query projection for the event timeline. The objective + username are
 * resolved via join in the query, so no lazy-loading is needed at render time.
 */
public interface OrderEventRow {
    String getEventType();
    Instant getAt();
    String getObjective();
    String getUsername();
    String getMetadataJson();
}
