package com.bellgado.logistics_ted.repository;

import com.bellgado.logistics_ted.domain.HouseOrder;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface HouseOrderRepository extends JpaRepository<HouseOrder, Integer> {

    @Query("SELECT o FROM HouseOrder o JOIN FETCH o.house LEFT JOIN FETCH o.createdBy LEFT JOIN FETCH o.items i LEFT JOIN FETCH i.material ORDER BY o.createdAt DESC")
    List<HouseOrder> findAllWithDetails();
}
