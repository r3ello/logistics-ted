package com.bellgado.logistics_ted.repository;

import com.bellgado.logistics_ted.domain.Inventory;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface InventoryRepository extends JpaRepository<Inventory, Integer> {

    @Query("""
        SELECT i FROM Inventory i
        JOIN FETCH i.warehouse w
        JOIN FETCH w.house h
        JOIN FETCH i.material m
        ORDER BY h.id, m.id
        """)
    List<Inventory> findAllWithJoins();

    @Query("""
        SELECT i FROM Inventory i
        JOIN FETCH i.warehouse w
        JOIN FETCH w.house h
        JOIN FETCH i.material m
        WHERE i.quantity > 0 AND h.lat IS NOT NULL AND h.id <> :excludedHouseId
        ORDER BY h.id, m.id
        """)
    List<Inventory> findCandidatesForOrder(Integer excludedHouseId);

    Optional<Inventory> findByWarehouseIdAndMaterialId(Integer warehouseId, Integer materialId);
}
