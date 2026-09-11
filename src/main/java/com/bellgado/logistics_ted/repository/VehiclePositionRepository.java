package com.bellgado.logistics_ted.repository;

import com.bellgado.logistics_ted.domain.VehiclePosition;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface VehiclePositionRepository extends JpaRepository<VehiclePosition, Long> {

    boolean existsByVehicleIdAndTs(Integer vehicleId, Instant ts);

    /** Breadcrumb of one vehicle, {@code from} inclusive, {@code to} exclusive, oldest first. */
    @Query("SELECT p FROM VehiclePosition p WHERE p.vehicle.id = :vehicleId AND p.ts >= :from AND p.ts < :to "
        + "ORDER BY p.ts")
    List<VehiclePosition> findWindow(Integer vehicleId, Instant from, Instant to);
}
