package com.bellgado.logistics_ted.repository;

import com.bellgado.logistics_ted.domain.Warehouse;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WarehouseRepository extends JpaRepository<Warehouse, Integer> {

    Optional<Warehouse> findByHouseId(Integer houseId);
}
