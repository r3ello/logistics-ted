package com.bellgado.logistics_ted.repository;

import com.bellgado.logistics_ted.domain.ElectricBox;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ElectricBoxRepository extends JpaRepository<ElectricBox, Integer> {
    Optional<ElectricBox> findByHouseId(Integer houseId);
    Optional<ElectricBox> findByToken(String token);
    boolean existsByHouseId(Integer houseId);
}
