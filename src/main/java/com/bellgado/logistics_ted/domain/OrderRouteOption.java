package com.bellgado.logistics_ted.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
    name = "order_route_option",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_option_order_objective",
        columnNames = {"order_id", "objective"}
    )
)
@Getter
@Setter
@NoArgsConstructor
public class OrderRouteOption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private CustomerOrder order;

    @Column(nullable = false, length = 32)
    private String objective;

    @Column(name = "is_primary", nullable = false)
    private boolean primary;

    @Column(name = "sequence_index", nullable = false)
    private int sequenceIndex;

    @Column(name = "total_distance_km", nullable = false)
    private int totalDistanceKm;

    @Column(name = "total_minutes", nullable = false)
    private int totalMinutes;

    @Column(name = "total_stops", nullable = false)
    private int totalStops;

    @Column(name = "supplier_stops_count", nullable = false)
    private int supplierStopsCount;

    @Column(name = "fully_fulfilled", nullable = false)
    private boolean fullyFulfilled;

    @Column(name = "house_ids_signature", nullable = false, columnDefinition = "text")
    private String houseIdsSignature;

    @Column(name = "maps_url", columnDefinition = "text")
    private String mapsUrl;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_json", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payloadJson = new LinkedHashMap<>();

    @Column(name = "first_viewed_at")
    private Instant firstViewedAt;

    @Column(name = "last_viewed_at")
    private Instant lastViewedAt;

    @Column(name = "view_count", nullable = false)
    private int viewCount;
}
