package com.bellgado.logistics_ted.repository;

import com.bellgado.logistics_ted.domain.HouseStage;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface HouseStageRepository extends JpaRepository<HouseStage, Integer> {
    List<HouseStage> findByHouseIdOrderByStageOrder(Integer houseId);

    @Query(value = "SELECT DISTINCT ON (stage_order) stage_order, stage_name, stage_name_en FROM house_stage ORDER BY stage_order", nativeQuery = true)
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

    @Query("SELECT COUNT(s) FROM HouseStage s WHERE s.crewId = :crewId AND s.id <> :excludeId")
    long countOtherAssignments(Integer crewId, Integer excludeId);

    @Query(value = "SELECT stage_name FROM house_stage WHERE house_id = :houseId AND status = 'IN_PROGRESS' ORDER BY stage_order", nativeQuery = true)
    List<String> findAllInProgressStageNames(Integer houseId);

    @Query(value = "SELECT DISTINCT h.id, h.name FROM house_stage hs JOIN house h ON h.id = hs.house_id WHERE hs.crew_id = :crewId AND hs.status <> 'NOT_STARTED' ORDER BY h.name", nativeQuery = true)
    List<Object[]> findAssignedHousesForCrew(Integer crewId);

    @Query(value = """
        SELECT string_agg(DISTINCT hs.stage_name, ', ' ORDER BY hs.stage_name)
        FROM stage_type_crew stc
        JOIN house_stage hs ON hs.stage_order = stc.stage_order
        WHERE stc.crew_id = :crewId
        """, nativeQuery = true)
    String findStageNamesForCrew(Integer crewId);

    @Query(value = """
        SELECT c.id, c.name,
               w.id AS leader_id, w.name AS leader_name,
               string_agg(DISTINCT h2.name, ', ' ORDER BY h2.name) AS assigned_houses
        FROM stage_type_crew stc
        JOIN crew c ON c.id = stc.crew_id
        LEFT JOIN worker w ON w.crew_id = c.id AND w.role = 'CREW_LEADER'
        LEFT JOIN house_stage hs2 ON hs2.crew_id = c.id AND hs2.status <> 'NOT_STARTED'
        LEFT JOIN house h2 ON h2.id = hs2.house_id
        WHERE stc.stage_order = :stageOrder
        GROUP BY c.id, c.name, w.id, w.name
        ORDER BY c.name
        """, nativeQuery = true)
    List<Object[]> findCrewsForStage(Integer stageOrder);
}
