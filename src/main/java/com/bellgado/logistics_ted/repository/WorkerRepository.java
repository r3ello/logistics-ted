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

    java.util.Optional<Worker> findByUsername(String username);

    /** All workers with their crew + crew's house/manager/leader eagerly fetched. */
    @Query("""
        SELECT DISTINCT w FROM Worker w
        LEFT JOIN FETCH w.crew c
        LEFT JOIN FETCH c.house
        LEFT JOIN FETCH c.manager
        LEFT JOIN FETCH c.leader
        ORDER BY w.name
        """)
    List<Worker> findAllWithCrew();

    /** Workers whose crew is assigned to any stage on this house (via house_stage). */
    @Query(value = """
        SELECT DISTINCT w.* FROM worker w
        JOIN crew c ON c.id = w.crew_id
        JOIN house_stage hs ON hs.crew_id = c.id AND hs.house_id = :houseId
        ORDER BY w.name
        """, nativeQuery = true)
    List<Worker> findByHouseId(@Param("houseId") Integer houseId);
}
