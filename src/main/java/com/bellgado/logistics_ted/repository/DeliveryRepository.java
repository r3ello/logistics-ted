package com.bellgado.logistics_ted.repository;

import com.bellgado.logistics_ted.domain.Delivery;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeliveryRepository extends JpaRepository<Delivery, Integer> {

    @Query("SELECT d FROM Delivery d JOIN FETCH d.order o JOIN FETCH o.house LEFT JOIN FETCH d.responsible LEFT JOIN FETCH d.items i LEFT JOIN FETCH i.material ORDER BY d.deliveredAt DESC")
    List<Delivery> findAllWithDetails();

    @Query("SELECT d FROM Delivery d LEFT JOIN FETCH d.responsible LEFT JOIN FETCH d.items i LEFT JOIN FETCH i.material WHERE d.order.id = :orderId ORDER BY d.deliveredAt DESC")
    List<Delivery> findByOrderId(@Param("orderId") Integer orderId);
}
