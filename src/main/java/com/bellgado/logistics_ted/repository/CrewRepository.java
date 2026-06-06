package com.bellgado.logistics_ted.repository;

import com.bellgado.logistics_ted.domain.Crew;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CrewRepository extends JpaRepository<Crew, Integer> {
    List<Crew> findByManagerId(Integer managerId);
}
