package com.bellgado.logistics_ted.repository;

import com.bellgado.logistics_ted.domain.Vehicle;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface VehicleRepository extends JpaRepository<Vehicle, Integer> {

    Optional<Vehicle> findByGpsCode(String gpsCode);

    /** The vehicles whose trips the poller keeps in sync. */
    @Query("SELECT v.gpsCode FROM Vehicle v WHERE v.active = true ORDER BY v.id")
    List<String> findActiveCodes();

    @Query("SELECT v FROM Vehicle v LEFT JOIN FETCH v.driverWorker ORDER BY v.id")
    List<Vehicle> findAllWithDriver();
}
