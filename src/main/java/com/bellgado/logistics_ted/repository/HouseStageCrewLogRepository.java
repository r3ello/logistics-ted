package com.bellgado.logistics_ted.repository;

import com.bellgado.logistics_ted.domain.HouseStageCrewLog;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HouseStageCrewLogRepository extends JpaRepository<HouseStageCrewLog, Long> {

    @Query(value = """
        SELECT l.* FROM house_stage_crew_log l
        WHERE l.crew_id = (
            SELECT w.crew_id FROM worker w WHERE w.id = :workerId AND w.crew_id IS NOT NULL LIMIT 1
        )
        ORDER BY l.house_id, l.stage_order, l.logged_at ASC
        """, nativeQuery = true)
    List<HouseStageCrewLog> findByWorkerId(@Param("workerId") Integer workerId);

    @Query(value = """
        SELECT l.* FROM house_stage_crew_log l
        WHERE l.crew_id = (
            SELECT w.crew_id FROM worker w WHERE w.id = :workerId AND w.crew_id IS NOT NULL LIMIT 1
        )
        AND (:fromDate IS NULL OR COALESCE(l.start_date, l.logged_at::date) >= CAST(:fromDate AS date))
        AND (:toDate   IS NULL OR COALESCE(l.end_date,   l.logged_at::date) <= CAST(:toDate   AS date))
        ORDER BY l.house_id, l.stage_order, l.logged_at ASC
        """, nativeQuery = true)
    List<HouseStageCrewLog> findByWorkerIdAndDateRange(
        @Param("workerId") Integer workerId,
        @Param("fromDate") String fromDate,
        @Param("toDate") String toDate);

    @Query(value = """
        SELECT l.* FROM house_stage_crew_log l
        WHERE l.crew_id = :crewId
        ORDER BY l.house_id, l.stage_order, l.logged_at ASC
        """, nativeQuery = true)
    List<HouseStageCrewLog> findByCrewId(@Param("crewId") Integer crewId);

    @Query(value = """
        SELECT l.* FROM house_stage_crew_log l
        WHERE l.crew_id = :crewId
        AND (:fromDate IS NULL OR COALESCE(l.start_date, l.logged_at::date) >= CAST(:fromDate AS date))
        AND (:toDate   IS NULL OR COALESCE(l.end_date,   l.logged_at::date) <= CAST(:toDate   AS date))
        ORDER BY l.house_id, l.stage_order, l.logged_at ASC
        """, nativeQuery = true)
    List<HouseStageCrewLog> findByCrewIdAndDateRange(
        @Param("crewId") Integer crewId,
        @Param("fromDate") String fromDate,
        @Param("toDate") String toDate);

    @Query(value = """
        SELECT l.stage_order,
               AVG(l.end_date - l.start_date) AS avg_days,
               MAX(l.stage_name)    AS stage_name,
               MAX(l.stage_name_en) AS stage_name_en
        FROM house_stage_crew_log l
        WHERE l.status = 'DONE'
          AND l.start_date IS NOT NULL
          AND l.end_date   IS NOT NULL
        GROUP BY l.stage_order
        ORDER BY l.stage_order
        """, nativeQuery = true)
    List<Object[]> avgDaysPerStage();

    /** Latest log entry for a given house stage and crew — used to detect duplicate writes. */
    @Query(value = """
        SELECT l.* FROM house_stage_crew_log l
        WHERE l.house_id = :houseId AND l.stage_order = :stageOrder AND l.crew_id = :crewId
        ORDER BY l.logged_at DESC LIMIT 1
        """, nativeQuery = true)
    java.util.Optional<HouseStageCrewLog> findLatest(
        @Param("houseId") Integer houseId,
        @Param("stageOrder") Integer stageOrder,
        @Param("crewId") Integer crewId);
}
