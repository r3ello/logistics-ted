package com.bellgado.logistics_ted.repository;

import com.bellgado.logistics_ted.domain.DepotInventory;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DepotInventoryRepository extends JpaRepository<DepotInventory, Integer> {

    @Query("""
        SELECT di FROM DepotInventory di
        JOIN FETCH di.depot d
        JOIN FETCH di.material m
        WHERE di.material.id IN :materialIds
          AND di.quantity > 0
          AND d.lat IS NOT NULL
          AND d.lng IS NOT NULL
        ORDER BY d.id, m.id
        """)
    List<DepotInventory> findStocked(@Param("materialIds") Collection<Integer> materialIds);

    @Query("""
        SELECT di FROM DepotInventory di
        JOIN FETCH di.depot d
        JOIN FETCH di.material m
        WHERE di.quantity > 0
        ORDER BY d.id, m.id
        """)
    List<DepotInventory> findAllStocked();

    @Query("""
        SELECT di FROM DepotInventory di
        JOIN FETCH di.depot d
        JOIN FETCH di.material m
        ORDER BY d.id, m.id
        """)
    List<DepotInventory> findAllWithJoins();

    Optional<DepotInventory> findByDepotIdAndMaterialId(Integer depotId, Integer materialId);
}
