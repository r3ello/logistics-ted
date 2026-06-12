package com.bellgado.logistics_ted.repository;

import com.bellgado.logistics_ted.domain.ElectricCircuit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ElectricCircuitRepository extends JpaRepository<ElectricCircuit, Integer> {
    @Modifying
    @Query("DELETE FROM ElectricCircuit c WHERE c.box.id = :boxId")
    void deleteByBoxId(Integer boxId);
}
