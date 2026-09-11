package com.bellgado.logistics_ted.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A GPS.bg-tracked vehicle. The provider owns {@code providerName}, {@code regNumber} and every
 * {@code last*} field (overwritten on each poll); {@code alias}, {@code notes}, {@code active} and
 * {@code driverWorker} are ours and the sync never touches them. See {@code V15__vehicles.sql}.
 */
@Entity
@Table(name = "vehicle")
@Getter
@Setter
@NoArgsConstructor
public class Vehicle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "gps_code", nullable = false, unique = true, length = 32)
    private String gpsCode;

    @Column(name = "provider_name", length = 255)
    private String providerName;

    @Column(name = "reg_number", length = 32)
    private String regNumber;

    @Column(length = 255)
    private String alias;

    @Column(columnDefinition = "TEXT")
    private String notes;

    /** Inactive vehicles still get live status but no trip syncs. */
    @Column(nullable = false)
    private boolean active = true;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "driver_worker_id")
    private Worker driverWorker;

    @Column(name = "last_ts")
    private Instant lastTs;

    @Column(name = "last_lat")
    private Double lastLat;

    @Column(name = "last_lng")
    private Double lastLng;

    @Column(name = "last_speed_kmh")
    private Double lastSpeedKmh;

    @Column(name = "last_heading")
    private Integer lastHeading;

    private Boolean ignition;

    /** GPS.bg IsOnline. Not trustworthy on its own — judge staleness from {@link #lastTs}. */
    private Boolean online;

    @Column(name = "last_driver", length = 255)
    private String lastDriver;

    @Column(name = "last_address", length = 500)
    private String lastAddress;

    @Column(name = "odometer_km")
    private Double odometerKm;

    @Column(name = "battery_v")
    private Double batteryV;

    @Column(name = "ext_power_v")
    private Double extPowerV;

    /** The whole GetStatus struct: analog inputs, digital I/O, tachograph fields. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "last_status_json", columnDefinition = "jsonb")
    private Map<String, Object> lastStatusJson;

    @Column(name = "status_synced_at")
    private Instant statusSyncedAt;

    @Column(name = "routes_synced_at")
    private Instant routesSyncedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
