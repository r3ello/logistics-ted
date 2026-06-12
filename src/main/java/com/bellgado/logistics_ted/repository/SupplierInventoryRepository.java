package com.bellgado.logistics_ted.repository;

import com.bellgado.logistics_ted.domain.SupplierInventory;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SupplierInventoryRepository extends JpaRepository<SupplierInventory, Integer> {

    @Query("""
        SELECT si FROM SupplierInventory si
        JOIN FETCH si.supplier s
        JOIN FETCH si.material m
        WHERE si.material.id IN :materialIds
          AND si.quantity > 0
          AND s.lat IS NOT NULL
          AND s.lng IS NOT NULL
        ORDER BY s.id, m.id
        """)
    List<SupplierInventory> findStocked(@Param("materialIds") Collection<Integer> materialIds);

    @Query("""
        SELECT si FROM SupplierInventory si
        JOIN FETCH si.supplier s
        JOIN FETCH si.material m
        WHERE si.quantity > 0
        ORDER BY s.id, m.id
        """)
    List<SupplierInventory> findAllStocked();
}
