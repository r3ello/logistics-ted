package com.bellgado.logistics_ted.repository;

import com.bellgado.logistics_ted.domain.OrderRouteOption;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRouteOptionRepository extends JpaRepository<OrderRouteOption, Long> {

    Optional<OrderRouteOption> findByOrder_IdAndObjective(Long orderId, String objective);
}
