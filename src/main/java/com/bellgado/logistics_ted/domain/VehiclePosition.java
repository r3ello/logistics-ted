package com.bellgado.logistics_ted.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One breadcrumb point: stored each time GetStatus reports a new {@code LastTS} for a vehicle.
 * GPS.bg has no point-by-point track function, so these points are the track.
 */
@Entity
@Table(name = "vehicle_position")
@Getter
@Setter
@NoArgsConstructor
public class VehiclePosition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vehicle_id", nullable = false)
    private Vehicle vehicle;

    @Column(nullable = false)
    private Instant ts;

    @Column(nullable = false)
    private double lat;

    @Column(nullable = false)
    private double lng;

    @Column(name = "speed_kmh")
    private Double speedKmh;

    private Integer heading;

    private Boolean ignition;
}
