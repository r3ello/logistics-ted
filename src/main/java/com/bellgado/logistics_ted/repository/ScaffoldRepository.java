package com.bellgado.logistics_ted.repository;

import com.bellgado.logistics_ted.domain.Scaffold;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScaffoldRepository extends JpaRepository<Scaffold, Integer> {
    boolean existsByHouseIdAndIdNot(Integer houseId, Integer excludeId);
    Optional<Scaffold> findByHouseId(Integer houseId);
}
