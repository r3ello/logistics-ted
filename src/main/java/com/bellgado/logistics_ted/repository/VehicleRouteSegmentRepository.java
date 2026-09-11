package com.bellgado.logistics_ted.repository;

import com.bellgado.logistics_ted.domain.VehicleRouteSegment;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface VehicleRouteSegmentRepository extends JpaRepository<VehicleRouteSegment, Long> {

    /** Segments starting in [from, to), in order; a zero-length stop sorts before a drive at the same second. */
    @Query("SELECT s FROM VehicleRouteSegment s WHERE s.vehicle.id = :vehicleId "
        + "AND s.startTs >= :from AND s.startTs < :to ORDER BY s.startTs, s.moving")
    List<VehicleRouteSegment> findWindow(Integer vehicleId, Instant from, Instant to);

    @Query("SELECT s FROM VehicleRouteSegment s WHERE s.vehicle.id = :vehicleId "
        + "AND s.startTs = :startTs AND s.moving = :moving")
    Optional<VehicleRouteSegment> findOne(Integer vehicleId, Instant startTs, boolean moving);

    /** Clears a re-fetched window, {@code to} inclusive (it is the end of the provider query). */
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM VehicleRouteSegment s WHERE s.vehicle.id = :vehicleId "
        + "AND s.startTs >= :from AND s.startTs <= :to")
    int deleteWindow(Integer vehicleId, Instant from, Instant to);
}
