package com.bellgado.logistics_ted.repository;

import com.bellgado.logistics_ted.domain.HouseStage;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface HouseStageRepository extends JpaRepository<HouseStage, Integer> {
    List<HouseStage> findByHouseIdOrderByStageOrder(Integer houseId);

    java.util.Optional<HouseStage> findByHouseIdAndStageOrder(Integer houseId, Integer stageOrder);

    /** Every (house) stage cell assigned to a crew — what its leader may raise a material order for. */
    @Query("SELECT s FROM HouseStage s JOIN FETCH s.house WHERE s.crewId = :crewId ORDER BY s.house.name, s.stageOrder")
    List<HouseStage> findByCrewIdWithHouse(Integer crewId);

    @Query(value = "SELECT stage_order, stage_name, stage_name_en FROM stage_type ORDER BY CASE WHEN stage_name_en = 'Completion' THEN 1 ELSE 0 END, stage_order", nativeQuery = true)
    List<Object[]> findDistinctStageTypes();

    List<HouseStage> findByStageOrder(Integer stageOrder);

    @Modifying
    @Query("UPDATE HouseStage s SET s.stageName = :name WHERE s.stageOrder = :order")
    void renameStage(Integer order, String name);

    @Modifying
    @Query("UPDATE HouseStage s SET s.stageNameEn = :name WHERE s.stageOrder = :order")
    void renameStageEn(Integer order, String name);

    @Modifying
    @Query("DELETE FROM HouseStage s WHERE s.stageOrder = :order")
    void deleteByStageOrder(Integer order);

    @Query("SELECT MAX(s.stageOrder) FROM HouseStage s")
    Integer maxStageOrder();

    @Modifying
    @Query("UPDATE HouseStage s SET s.workerName = :leaderName WHERE s.crewId = :crewId")
    void syncLeaderNameForCrew(Integer crewId, String leaderName);

    @Query("SELECT COUNT(s) FROM HouseStage s WHERE s.crewId = :crewId AND s.id <> :excludeId")
    long countOtherAssignments(Integer crewId, Integer excludeId);

    @Query(value = "SELECT stage_name FROM house_stage WHERE house_id = :houseId AND status = 'IN_PROGRESS' ORDER BY stage_order", nativeQuery = true)
    List<String> findAllInProgressStageNames(Integer houseId);

    @Query(value = "SELECT DISTINCT h.id, h.name FROM house_stage hs JOIN house h ON h.id = hs.house_id WHERE hs.crew_id = :crewId AND hs.status = 'IN_PROGRESS' ORDER BY h.name", nativeQuery = true)
    List<Object[]> findAssignedHousesForCrew(Integer crewId);

    @Query(value = """
        SELECT string_agg(DISTINCT st.stage_name, ', ' ORDER BY st.stage_name)
        FROM crew_stage_type cst
        JOIN stage_type st ON st.stage_order = cst.stage_order
        WHERE cst.crew_id = :crewId
        """, nativeQuery = true)
    String findStageNamesForCrew(Integer crewId);

    @Query(value = """
        SELECT c.id, c.name,
               w.id AS leader_id, w.name AS leader_name,
               string_agg(DISTINCT h2.name, ', ' ORDER BY h2.name) AS assigned_houses
        FROM crew c
        JOIN crew_stage_type cst ON cst.crew_id = c.id AND cst.stage_order = :stageOrder
        LEFT JOIN worker w ON w.id = c.leader_id
        LEFT JOIN house_stage hs2 ON hs2.crew_id = c.id AND hs2.status <> 'NOT_STARTED'
        LEFT JOIN house h2 ON h2.id = hs2.house_id
        GROUP BY c.id, c.name, w.id, w.name
        ORDER BY c.name
        """, nativeQuery = true)
    List<Object[]> findCrewsForStage(Integer stageOrder);

    @Query(value = "SELECT stage_name FROM stage_type WHERE stage_order = :stageOrder LIMIT 1", nativeQuery = true)
    String findStageNameByOrder(Integer stageOrder);

    /** All house→crew mappings: [house_id, crew_id, crew_name] for every crew assigned to any stage. */
    @Query(value = """
        SELECT DISTINCT hs.house_id, c.id AS crew_id, c.name AS crew_name
        FROM house_stage hs
        JOIN crew c ON c.id = hs.crew_id
        ORDER BY hs.house_id, c.name
        """, nativeQuery = true)
    List<Object[]> findAllHouseCrewMappings();

    /** Batch: for every crew, the distinct houses it is assigned to (status <> NOT_STARTED).
     *  Returns [crew_id, house_id, house_name]. */
    @Query(value = """
        SELECT DISTINCT hs.crew_id, h.id, h.name
        FROM house_stage hs
        JOIN house h ON h.id = hs.house_id
        WHERE hs.crew_id IS NOT NULL AND hs.status = 'IN_PROGRESS'
        ORDER BY hs.crew_id, h.name
        """, nativeQuery = true)
    List<Object[]> findAllAssignedHousesPerCrew();

    /** Batch: for every crew, the comma-aggregated stage names via crew_stage_type.
     *  Returns [crew_id, stage_names]. */
    @Query(value = """
        SELECT cst.crew_id,
               string_agg(DISTINCT st.stage_name, ', ' ORDER BY st.stage_name) AS stage_names
        FROM crew_stage_type cst
        JOIN stage_type st ON st.stage_order = cst.stage_order
        GROUP BY cst.crew_id
        """, nativeQuery = true)
    List<Object[]> findAllStageNamesPerCrew();

    /** All house stages with house eagerly fetched — avoids N+1 on house lazy load. */
    @Query("SELECT s FROM HouseStage s JOIN FETCH s.house ORDER BY s.house.id, s.stageOrder")
    List<HouseStage> findAllWithHouse();

    /** Batch: all crews per stage type with leader and assigned houses.
     *  Returns [stage_order, crew_id, crew_name, leader_id, leader_name, assigned_houses, assigned_houses_json]. */
    @Query(value = """
        SELECT cst.stage_order, c.id, c.name,
               w.id AS leader_id, w.name AS leader_name,
               string_agg(DISTINCT h2.name, ', ' ORDER BY h2.name) AS assigned_houses,
               COALESCE(json_agg(DISTINCT jsonb_build_object('id', h2.id, 'name', h2.name)) FILTER (WHERE h2.id IS NOT NULL), '[]') AS assigned_houses_json
        FROM crew_stage_type cst
        JOIN crew c ON c.id = cst.crew_id
        LEFT JOIN worker w ON w.id = c.leader_id
        LEFT JOIN house_stage hs2 ON hs2.crew_id = c.id AND hs2.status = 'IN_PROGRESS'
        LEFT JOIN house h2 ON h2.id = hs2.house_id
        GROUP BY cst.stage_order, c.id, c.name, w.id, w.name
        ORDER BY cst.stage_order, c.name
        """, nativeQuery = true)
    List<Object[]> findAllCrewsPerStage();
}
