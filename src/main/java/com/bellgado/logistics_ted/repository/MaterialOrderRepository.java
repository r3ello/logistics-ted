package com.bellgado.logistics_ted.repository;

import com.bellgado.logistics_ted.domain.MaterialOrder;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface MaterialOrderRepository extends JpaRepository<MaterialOrder, Integer> {

    @Query("SELECT o FROM MaterialOrder o JOIN FETCH o.house LEFT JOIN FETCH o.createdBy LEFT JOIN FETCH o.items i LEFT JOIN FETCH i.material ORDER BY o.createdAt DESC")
    List<MaterialOrder> findAllWithDetails();
}
