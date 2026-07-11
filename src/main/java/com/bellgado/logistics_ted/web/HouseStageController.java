package com.bellgado.logistics_ted.web;

import com.bellgado.logistics_ted.domain.Crew;
import com.bellgado.logistics_ted.domain.House;
import com.bellgado.logistics_ted.domain.HouseStage;
import com.bellgado.logistics_ted.domain.HouseStageCrewLog;
import com.bellgado.logistics_ted.repository.CrewRepository;
import com.bellgado.logistics_ted.repository.HouseRepository;
import com.bellgado.logistics_ted.repository.HouseStageCrewLogRepository;
import com.bellgado.logistics_ted.repository.HouseStageRepository;
import com.bellgado.logistics_ted.repository.WorkerRepository;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class HouseStageController {

    @Value("${app.timezone:Europe/Sofia}")
    private String appTimezone;

    private java.time.LocalDate today() {
        return java.time.LocalDate.now(java.time.ZoneId.of(appTimezone));
    }

    private final HouseStageRepository        stages;
    private final HouseRepository             houses;
    private final CrewRepository              crews;
    private final WorkerRepository            workers;
    private final HouseStageCrewLogRepository stageLogs;

    public HouseStageController(HouseStageRepository stages, HouseRepository houses,
                                CrewRepository crews, WorkerRepository workers,
                                HouseStageCrewLogRepository stageLogs) {
        this.stages    = stages;
        this.houses    = houses;
        this.crews     = crews;
        this.workers   = workers;
        this.stageLogs = stageLogs;
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

        // Batch: all crews per stage in one query
        Map<Integer, List<Map<String, Object>>> crewsByStage = new HashMap<>();
        for (Object[] cr : stages.findAllCrewsPerStage()) {
            int stageOrder = ((Number) cr[0]).intValue();
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("crewId",             cr[1]);
            c.put("crewName",           cr[2]);
            c.put("leaderId",           cr[3]);
            c.put("leaderName",         cr[4]);
            c.put("assignedHouses",     cr[5]);
            c.put("assignedHousesJson", cr[6]);
            crewsByStage.computeIfAbsent(stageOrder, k -> new ArrayList<>()).add(c);
        }

        List<Map<String, Object>> columns = stageTypes.stream().map(row -> {
            Integer order = (Integer) row[0];
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("stageOrder",  order);
            m.put("stageName",   row[1]);
            m.put("stageNameEn", row[2]);
            m.put("crews", crewsByStage.getOrDefault(order, List.of()));
            return m;
        }).toList();

        // Batch: all house stages in one query, grouped by house
        Map<Integer, Map<Integer, HouseStage>> stagesByHouse = new HashMap<>();
        for (HouseStage hs : stages.findAllWithHouse()) {
            stagesByHouse
                .computeIfAbsent(hs.getHouse().getId(), k -> new HashMap<>())
                .put(hs.getStageOrder(), hs);
        }

        List<Map<String, Object>> rows = houses.findAll().stream()
            .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
            .map(h -> {
                Map<Integer, HouseStage> byOrder = stagesByHouse.getOrDefault(h.getId(), Map.of());
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

    // ── Worker journey: all house-stage history for a worker's crew ─────────────

    @GetMapping("/workers/{workerId}/journey")
    @Transactional(readOnly = true)
    public ResponseEntity<?> workerJourney(
            @PathVariable Integer workerId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return workers.findById(workerId).map(w -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("workerId",   w.getId());
            result.put("workerName", w.getName());
            result.put("role",       w.getRole());
            result.put("crewId",     w.getCrew() != null ? w.getCrew().getId()   : null);
            result.put("crewName",   w.getCrew() != null ? w.getCrew().getName() : null);
            result.put("trade",      w.getTrade());
            result.put("location",   w.getLocation());

            String fromDate = (from != null && !from.isBlank()) ? from : null;
            String toDate   = (to   != null && !to.isBlank())   ? to   : null;
            List<HouseStageCrewLog> rawLogs = (fromDate != null || toDate != null)
                ? stageLogs.findByWorkerIdAndDateRange(workerId, fromDate, toDate)
                : stageLogs.findByWorkerId(workerId);

            List<Map<String, Object>> history = rawLogs.stream().map(l -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id",          l.getId());
                m.put("houseId",     l.getHouseId());
                m.put("houseName",   l.getHouseName());
                m.put("stageOrder",  l.getStageOrder());
                m.put("stageName",   l.getStageName());
                m.put("stageNameEn", l.getStageNameEn());
                m.put("crewId",      l.getCrewId());
                m.put("crewName",    l.getCrewName());
                m.put("status",      l.getStatus());
                m.put("startDate",   l.getStartDate() != null ? l.getStartDate().toString() : null);
                m.put("endDate",     l.getEndDate()   != null ? l.getEndDate().toString()   : null);
                m.put("loggedAt",    l.getLoggedAt().toString());
                return m;
            }).toList();
            result.put("history", history);
            return ResponseEntity.ok(result);
        }).orElse(ResponseEntity.notFound().build());
    }

    // ── Stage benchmarks: average days per stage across all crews ────────────────

    @GetMapping("/stages/avg-days")
    @Transactional(readOnly = true)
    public ResponseEntity<?> avgDaysPerStage() {
        var result = new java.util.LinkedHashMap<Integer, java.util.Map<String, Object>>();
        for (Object[] row : stageLogs.avgDaysPerStage()) {
            Integer stageOrder = ((Number) row[0]).intValue();
            Double  avgDays    = row[1] != null ? ((Number) row[1]).doubleValue() : null;
            if (avgDays == null) continue;
            var m = new java.util.LinkedHashMap<String, Object>();
            m.put("avgDays",    avgDays);
            m.put("stageName",   row[2]);
            m.put("stageNameEn", row[3]);
            result.put(stageOrder, m);
        }
        return ResponseEntity.ok(result);
    }

    // ── House timeline: all stages with dates for Gantt view ─────────────────────

    @GetMapping("/houses/{houseId}/timeline")
    @Transactional(readOnly = true)
    public ResponseEntity<?> houseTimeline(@PathVariable Integer houseId) {
        return houses.findById(houseId).map(h -> {
            List<Map<String, Object>> stageList = stages.findByHouseIdOrderByStageOrder(houseId).stream().map(s -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("stageOrder",  s.getStageOrder());
                m.put("stageName",   s.getStageName());
                m.put("stageNameEn", s.getStageNameEn());
                m.put("status",      s.getStatus());
                m.put("startDate",   s.getStartDate()  != null ? s.getStartDate().toString()  : null);
                m.put("endDate",     s.getEndDate()    != null ? s.getEndDate().toString()    : null);
                m.put("crewId",      s.getCrewId());
                m.put("crewName",    s.getCrewId() != null ? crews.findById(s.getCrewId()).map(Crew::getName).orElse(null) : null);
                return m;
            }).collect(Collectors.toList());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("houseId",   h.getId());
            result.put("houseName", h.getName());
            result.put("location",  h.getLocation());
            result.put("stages",    stageList);
            return ResponseEntity.ok(result);
        }).orElse(ResponseEntity.notFound().build());
    }

    // ── Crew history: all house-stage history for a crew ─────────────────────────

    @GetMapping("/crews/{crewId}/history")
    @Transactional(readOnly = true)
    public ResponseEntity<?> crewHistory(
            @PathVariable Integer crewId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return crews.findById(crewId).map(c -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("crewId",      c.getId());
            result.put("crewName",    c.getName());
            result.put("stageOrder",  c.getStageOrder());
            result.put("leaderId",    c.getLeader() != null ? c.getLeader().getId()   : null);
            result.put("leaderName",  c.getLeader() != null ? c.getLeader().getName() : null);

            String fromDate = (from != null && !from.isBlank()) ? from : null;
            String toDate   = (to   != null && !to.isBlank())   ? to   : null;
            List<HouseStageCrewLog> rawLogs = (fromDate != null || toDate != null)
                ? stageLogs.findByCrewIdAndDateRange(crewId, fromDate, toDate)
                : stageLogs.findByCrewId(crewId);

            List<Map<String, Object>> history = rawLogs.stream().map(l -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id",          l.getId());
                m.put("houseId",     l.getHouseId());
                m.put("houseName",   l.getHouseName());
                m.put("stageOrder",  l.getStageOrder());
                m.put("stageName",   l.getStageName());
                m.put("stageNameEn", l.getStageNameEn());
                m.put("status",      l.getStatus());
                m.put("startDate",   l.getStartDate() != null ? l.getStartDate().toString() : null);
                m.put("endDate",     l.getEndDate()   != null ? l.getEndDate().toString()   : null);
                return m;
            }).toList();
            result.put("history", history);
            return ResponseEntity.ok(result);
        }).orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/house-stages/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable Integer id, @RequestBody Map<String, Object> body) {
        return stages.findById(id).map(s -> {
            Integer oldCrewId = s.getCrewId();

            if (body.containsKey("crewId")) {
                Integer newCrewId = body.get("crewId") != null ? Integer.valueOf(body.get("crewId").toString()) : null;
                s.setCrewId(newCrewId);

                // Assign new crew → house only if stage is active
                if (newCrewId != null && "IN_PROGRESS".equals(s.getStatus())) {
                    syncCrewHouse(newCrewId, s.getHouse());
                }

                // Clear old crew's house if they have no remaining assignments on any house
                if (oldCrewId != null && !oldCrewId.equals(newCrewId)) {
                    resyncCrewHouseAfterRemoval(oldCrewId, id);
                }
            }
            if (body.containsKey("workerName"))
                s.setWorkerName(body.get("workerName") != null ? body.get("workerName").toString() : null);
            if (body.containsKey("status") && body.get("status") != null) {
                String newStatus = body.get("status").toString();
                String oldStatus = s.getStatus();
                s.setStatus(newStatus);
                // Auto-populate dates on status transitions
                if ("IN_PROGRESS".equals(newStatus) && !"IN_PROGRESS".equals(oldStatus) && s.getStartDate() == null)
                    s.setStartDate(today());
                if ("DONE".equals(newStatus) && !"DONE".equals(oldStatus) && s.getEndDate() == null)
                    s.setEndDate(today());
                if ("NOT_STARTED".equals(newStatus)) {
                    s.setStartDate(null);
                    s.setEndDate(null);
                }
                // Sync crew.house_id on status transitions
                if ("IN_PROGRESS".equals(newStatus) && !"IN_PROGRESS".equals(oldStatus) && s.getCrewId() != null)
                    syncCrewHouse(s.getCrewId(), s.getHouse());
                if (!"IN_PROGRESS".equals(newStatus) && "IN_PROGRESS".equals(oldStatus) && s.getCrewId() != null)
                    resyncCrewHouseAfterRemoval(s.getCrewId(), -1);
            }
            if (body.containsKey("notes"))
                s.setNotes(body.get("notes") != null ? body.get("notes").toString() : null);
            s.setUpdatedAt(LocalDateTime.now());
            HouseStage saved = stages.save(s);

            // Append to history log when a crew is assigned and status is meaningful.
            // Rule: log only if status changed OR the previous log entry was DONE (new cycle after completion).
            if (saved.getCrewId() != null && (saved.getStatus().equals("IN_PROGRESS") || saved.getStatus().equals("DONE"))) {
                boolean shouldLog = true;
                var latest = stageLogs.findLatest(saved.getHouse().getId(), saved.getStageOrder(), saved.getCrewId());
                if (latest.isPresent()) {
                    String lastStatus = latest.get().getStatus();
                    // Suppress duplicate: same status and last entry was not DONE (not a new cycle)
                    if (lastStatus.equals(saved.getStatus()) && !"DONE".equals(lastStatus)) {
                        shouldLog = false;
                    }
                }
                if (shouldLog) {
                    HouseStageCrewLog log = new HouseStageCrewLog();
                    log.setHouseId(saved.getHouse().getId());
                    log.setHouseName(saved.getHouse().getName());
                    log.setStageOrder(saved.getStageOrder());
                    log.setStageName(saved.getStageName());
                    log.setStageNameEn(saved.getStageNameEn());
                    log.setCrewId(saved.getCrewId());
                    crews.findById(saved.getCrewId()).ifPresent(c -> log.setCrewName(c.getName()));
                    log.setStatus(saved.getStatus());
                    log.setStartDate(saved.getStatus().equals("DONE") ? saved.getStartDate() : today());
                    log.setEndDate(saved.getStatus().equals("DONE") ? saved.getEndDate() : null);
                    log.setLoggedAt(OffsetDateTime.now());
                    stageLogs.save(log);
                }
            }

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
            // worker.house is derived from crew.house — no direct field to sync
        });
    }

    private void resyncCrewHouseAfterRemoval(Integer crewId, Integer excludeStageId) {
        // Find remaining assignments for this crew (excluding the just-removed one)
        List<HouseStage> remaining = stages.findAll().stream()
            .filter(hs -> crewId.equals(hs.getCrewId()) && !hs.getId().equals(excludeStageId) && "IN_PROGRESS".equals(hs.getStatus()))
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
