package com.bellgado.logistics_ted.repository;

import com.bellgado.logistics_ted.domain.Material;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MaterialRepository extends JpaRepository<Material, Integer> {

    List<Material> findAllByOrderByIdAsc();
}
