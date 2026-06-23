package com.bellgado.logistics_ted.repository;

import com.bellgado.logistics_ted.domain.Depot;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DepotRepository extends JpaRepository<Depot, Integer> {

    List<Depot> findAllByOrderByIdAsc();
}
