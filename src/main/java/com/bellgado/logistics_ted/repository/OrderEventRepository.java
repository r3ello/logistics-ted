package com.bellgado.logistics_ted.repository;

import com.bellgado.logistics_ted.domain.OrderEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderEventRepository extends JpaRepository<OrderEvent, Long> {
    // Read-side queries live on CustomerOrderRepository as native projections so they
    // pull only the DTO columns. This interface keeps just the entity save() inherited
    // from JpaRepository, used by the write path in OrderHistoryService.
}
