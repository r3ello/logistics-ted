package com.bellgado.logistics_ted.web;

import com.bellgado.logistics_ted.domain.Crew;
import com.bellgado.logistics_ted.domain.House;
import com.bellgado.logistics_ted.domain.HouseStage;
import com.bellgado.logistics_ted.repository.CrewRepository;
import com.bellgado.logistics_ted.repository.HouseRepository;
import com.bellgado.logistics_ted.repository.HouseStageRepository;
import com.bellgado.logistics_ted.repository.WorkerRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class HouseStageController {

    private final HouseStageRepository stages;
    private final HouseRepository houses;
    private final CrewRepository crews;
    private final WorkerRepository workers;

    public HouseStageController(HouseStageRepository stages, HouseRepository houses,
                                CrewRepository crews, WorkerRepository workers) {
        this.stages  = stages;
        this.houses  = houses;
        this.crews   = crews;
        this.workers = workers;
    }

    // ── Stage type definitions ────────────────────────────────────────────────

    @GetMapping("/stage-types")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listStageTypes() {
        return stages.findDistinctStageTypes().stream().map(row -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("stageOrder",   row[0]);
            m.put("stageName",    row[1]);
            m.put("stageNameEn",  row[2]);
            return m;
        }).toList();
    }

    @PutMapping("/stage-types/{order}")
    @Transactional
    public ResponseEntity<?> renameStageType(@PathVariable Integer order, @RequestBody Map<String, Object> body) {
        if (body.get("stageName") == null) return ResponseEntity.badRequest().body(Map.of("error", "stageName required"));
        stages.renameStage(order, body.get("stageName").toString());
        if (body.get("stageNameEn") != null)
            stages.renameStageEn(order, body.get("stageNameEn").toString());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/stage-types")
    @Transactional
    public ResponseEntity<?> addStageType(@RequestBody Map<String, Object> body) {
        if (body.get("stageName") == null) return ResponseEntity.badRequest().body(Map.of("error", "stageName required"));
        String name = body.get("stageName").toString().trim();
        Integer maxOrder = stages.maxStageOrder();
        int newOrder = (maxOrder == null ? 0 : maxOrder) + 1;
        List<House> allHouses = houses.findAll();
        List<HouseStage> toSave = new ArrayList<>();
        String nameEn = body.get("stageNameEn") != null ? body.get("stageNameEn").toString().trim() : name;
        for (House h : allHouses) {
            HouseStage s = new HouseStage();
            s.setHouse(h);
            s.setStageOrder(newOrder);
            s.setStageName(name);
            s.setStageNameEn(nameEn);
            s.setStatus("NOT_STARTED");
            s.setUpdatedAt(LocalDateTime.now());
            toSave.add(s);
        }
        stages.saveAll(toSave);
        return ResponseEntity.ok(Map.of("stageOrder", newOrder, "stageName", name));
    }

    @DeleteMapping("/stage-types/{order}")
    @Transactional
    public ResponseEntity<?> deleteStageType(@PathVariable Integer order) {
        stages.deleteByStageOrder(order);
        return ResponseEntity.noContent().build();
    }

    // ── Crews for a stage type ────────────────────────────────────────────────

    @GetMapping("/stage-types/{order}/crews")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> crewsForStage(@PathVariable Integer order) {
        return stages.findCrewsForStage(order).stream().map(row -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("crewId",     row[0]);
            m.put("crewName",   row[1]);
            m.put("leaderId",   row[2]);
            m.put("leaderName", row[3]);
            return m;
        }).toList();
    }

    // ── Matrix: all houses × all stages ──────────────────────────────────────

    @GetMapping("/house-stages/matrix")
    @Transactional(readOnly = true)
    public Map<String, Object> matrix() {
        List<Object[]> stageTypes = stages.findDistinctStageTypes();
        List<Map<String, Object>> columns = stageTypes.stream().map(row -> {
            Integer order = (Integer) row[0];
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("stageOrder",  order);
            m.put("stageName",   row[1]);
            m.put("stageNameEn", row[2]);
            m.put("crews", stages.findCrewsForStage(order).stream().map(cr -> {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("crewId",         cr[0]);
                c.put("crewName",        cr[1]);
                c.put("leaderName",      cr[3]);
                c.put("assignedHouses",  cr[4]);
                return c;
            }).toList());
            return m;
        }).toList();

        List<Map<String, Object>> rows = houses.findAll().stream()
            .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
            .map(h -> {
                Map<Integer, HouseStage> byOrder = stages.findByHouseIdOrderByStageOrder(h.getId())
                    .stream().collect(Collectors.toMap(HouseStage::getStageOrder, s -> s));
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("houseId",   h.getId());
                row.put("houseName", h.getName());
                List<Map<String, Object>> cells = stageTypes.stream().map(st -> {
                    Integer order = (Integer) st[0];
                    HouseStage s = byOrder.get(order);
                    Map<String, Object> cell = new LinkedHashMap<>();
                    cell.put("id",         s != null ? s.getId() : null);
                    cell.put("stageOrder", order);
                    cell.put("status",     s != null ? s.getStatus() : "NOT_STARTED");
                    cell.put("crewId",     s != null ? s.getCrewId() : null);
                    cell.put("workerName", s != null ? s.getWorkerName() : null);
                    return cell;
                }).toList();
                row.put("stages", cells);
                return row;
            })
            .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("columns", columns);
        result.put("rows",    rows);
        return result;
    }

    // ── Per-house stage data ──────────────────────────────────────────────────

    @GetMapping("/houses/{houseId}/stages")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listForHouse(@PathVariable Integer houseId) {
        return stages.findByHouseIdOrderByStageOrder(houseId).stream().map(this::toDto).toList();
    }

    @PutMapping("/house-stages/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable Integer id, @RequestBody Map<String, Object> body) {
        return stages.findById(id).map(s -> {
            Integer oldCrewId = s.getCrewId();

            if (body.containsKey("crewId")) {
                Integer newCrewId = body.get("crewId") != null ? Integer.valueOf(body.get("crewId").toString()) : null;
                s.setCrewId(newCrewId);

                // Assign new crew → house and sync all their workers
                if (newCrewId != null) {
                    syncCrewHouse(newCrewId, s.getHouse());
                }

                // Clear old crew's house if they have no remaining assignments on any house
                if (oldCrewId != null && !oldCrewId.equals(newCrewId)) {
                    resyncCrewHouseAfterRemoval(oldCrewId, id);
                }
            }
            if (body.containsKey("workerName"))
                s.setWorkerName(body.get("workerName") != null ? body.get("workerName").toString() : null);
            if (body.containsKey("status") && body.get("status") != null)
                s.setStatus(body.get("status").toString());
            if (body.containsKey("notes"))
                s.setNotes(body.get("notes") != null ? body.get("notes").toString() : null);
            s.setUpdatedAt(LocalDateTime.now());
            HouseStage saved = stages.save(s);

            // Sync house current_phase to all IN_PROGRESS stages
            House house = s.getHouse();
            List<String> inProgress = stages.findAllInProgressStageNames(house.getId());
            house.setCurrentPhase(inProgress.isEmpty() ? null : String.join(", ", inProgress));
            houses.save(house);

            return ResponseEntity.ok(toDto(saved));
        }).orElse(ResponseEntity.notFound().build());
    }

    private void syncCrewHouse(Integer crewId, House house) {
        crews.findById(crewId).ifPresent(crew -> {
            crew.setHouse(house);
            crews.save(crew);
            workers.findByCrewId(crewId).forEach(w -> {
                w.setHouse(house);
                workers.save(w);
            });
        });
    }

    private void resyncCrewHouseAfterRemoval(Integer crewId, Integer excludeStageId) {
        // Find remaining assignments for this crew (excluding the just-removed one)
        List<HouseStage> remaining = stages.findAll().stream()
            .filter(hs -> crewId.equals(hs.getCrewId()) && !hs.getId().equals(excludeStageId))
            .toList();
        House newHouse = remaining.isEmpty() ? null : remaining.get(0).getHouse();
        syncCrewHouse(crewId, newHouse);
    }

    private Map<String, Object> toDto(HouseStage s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",          s.getId());
        m.put("houseId",     s.getHouse().getId());
        m.put("stageOrder",  s.getStageOrder());
        m.put("stageName",   s.getStageName());
        m.put("stageNameEn", s.getStageNameEn());
        m.put("crewId",      s.getCrewId());
        m.put("workerName",  s.getWorkerName());
        m.put("status",      s.getStatus());
        m.put("notes",       s.getNotes());
        m.put("updatedAt",   s.getUpdatedAt().toString());
        return m;
    }
}
