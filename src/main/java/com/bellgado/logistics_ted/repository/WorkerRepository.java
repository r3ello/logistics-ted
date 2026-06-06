package com.bellgado.logistics_ted.repository;

import com.bellgado.logistics_ted.domain.Worker;
import com.bellgado.logistics_ted.domain.WorkerRole;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkerRepository extends JpaRepository<Worker, Integer> {
    List<Worker> findByCrewId(Integer crewId);
    List<Worker> findByCrewIdAndRole(Integer crewId, WorkerRole role);
    List<Worker> findByRole(WorkerRole role);
}
