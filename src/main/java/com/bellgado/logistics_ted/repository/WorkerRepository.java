package com.bellgado.logistics_ted.repository;

import com.bellgado.logistics_ted.domain.Worker;
import com.bellgado.logistics_ted.domain.WorkerRole;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkerRepository extends JpaRepository<Worker, Integer> {
    List<Worker> findByCrewId(Integer crewId);
    List<Worker> findByCrewIdAndRole(Integer crewId, WorkerRole role);
    List<Worker> findByRole(WorkerRole role);

    /** Workers assigned to the crew that is working this house (eager-loads crew+house). */
    @Query("""
        SELECT w FROM Worker w
        JOIN FETCH w.crew c
        JOIN FETCH c.house h
        WHERE h.id = :houseId
        ORDER BY w.name
        """)
    List<Worker> findByHouseId(@Param("houseId") Integer houseId);
}
