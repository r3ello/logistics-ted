package com.bellgado.logistics_ted.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One VehicleRoutesList row: a driving segment ({@code moving}) or a stop. Unique on
 * (vehicle, start, moving); a re-fetched window replaces its rows, see
 * {@code VehicleTrackingService.applyRoutes}.
 */
@Entity
@Table(name = "vehicle_route_segment")
@Getter
@Setter
@NoArgsConstructor
public class VehicleRouteSegment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vehicle_id", nullable = false)
    private Vehicle vehicle;

    @Column(nullable = false)
    private boolean moving;

    @Column(name = "start_ts", nullable = false)
    private Instant startTs;

    @Column(name = "end_ts")
    private Instant endTs;

    @Column(name = "duration_s")
    private Integer durationS;

    @Column(name = "start_lat")
    private Double startLat;

    @Column(name = "start_lng")
    private Double startLng;

    @Column(name = "end_lat")
    private Double endLat;

    @Column(name = "end_lng")
    private Double endLng;

    @Column(name = "distance_km")
    private Double distanceKm;

    @Column(name = "avg_speed_kmh")
    private Double avgSpeedKmh;

    @Column(name = "max_speed_kmh")
    private Double maxSpeedKmh;

    @Column(name = "max_accel")
    private Double maxAccel;

    @Column(name = "max_decel")
    private Double maxDecel;

    @Column(name = "idle_s")
    private Integer idleS;

    /** GPS.bg Fuel_1: distance x a flat 8 L/100 km. An estimate, never a measurement. */
    @Column(name = "fuel_est_l")
    private Double fuelEstL;

    @Column(length = 255)
    private String driver;

    @Column(name = "start_place", length = 255)
    private String startPlace;

    @Column(name = "end_place", length = 255)
    private String endPlace;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_json", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> rawJson = new LinkedHashMap<>();

    @Column(name = "synced_at", nullable = false)
    private Instant syncedAt = Instant.now();
}
