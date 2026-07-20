package com.bellgado.logistics_ted.web;

import com.bellgado.logistics_ted.config.WorkerCredentialSeeder;
import com.bellgado.logistics_ted.domain.Worker;
import com.bellgado.logistics_ted.repository.CrewRepository;
import com.bellgado.logistics_ted.repository.HouseStageRepository;
import com.bellgado.logistics_ted.repository.WorkerRepository;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workers")
public class WorkerController {

    private final WorkerRepository        workers;
    private final CrewRepository          crews;
    private final HouseStageRepository    houseStages;
    private final WorkerCredentialSeeder  credentialSeeder;
    private final JdbcTemplate            jdbc;

    public WorkerController(WorkerRepository workers, CrewRepository crews,
                            HouseStageRepository houseStages, WorkerCredentialSeeder credentialSeeder,
                            JdbcTemplate jdbc) {
        this.workers          = workers;
        this.crews            = crews;
        this.houseStages      = houseStages;
        this.credentialSeeder = credentialSeeder;
        this.jdbc             = jdbc;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        // Pre-fetch all crew associations in one query
        List<Worker> all = workers.findAllWithCrew();

        // Pre-fetch stage names and assigned houses per crew in 2 batch queries
        Map<Integer, String> stageNamesPerCrew = new HashMap<>();
        for (Object[] row : houseStages.findAllStageNamesPerCrew()) {
            stageNamesPerCrew.put(((Number) row[0]).intValue(), (String) row[1]);
        }
        // Pre-fetch all stage names (bg + en) by stage_order
        Map<Integer, String[]> allStageNames = new HashMap<>();
        jdbc.query("SELECT stage_order, stage_name, stage_name_en FROM stage_type",
            rs -> { allStageNames.put(rs.getInt(1), new String[]{rs.getString(2), rs.getString(3)}); });
        Map<Integer, List<Map<String, Object>>> housesPerCrew = new HashMap<>();
        for (Object[] row : houseStages.findAllAssignedHousesPerCrew()) {
            int crewId = ((Number) row[0]).intValue();
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("houseId",   ((Number) row[1]).intValue());
            h.put("houseName", row[2]);
            housesPerCrew.computeIfAbsent(crewId, k -> new java.util.ArrayList<>()).add(h);
        }
        return all.stream().map(w -> toDto(w, stageNamesPerCrew, housesPerCrew, allStageNames)).toList();
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        String err = validateBody(body, true);
        if (err != null) return ResponseEntity.badRequest().body(Map.of("error", err));
        Worker w = applyBody(new Worker(), body);
        credentialSeeder.assignCredentials(w);
        return ResponseEntity.ok(toDto(workers.save(w)));
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable Integer id, @RequestBody Map<String, Object> body) {
        String err = validateBody(body, false);
        if (err != null) return ResponseEntity.badRequest().body(Map.of("error", err));
        return workers.findById(id).map(w -> {
            applyBody(w, body);
            // If promoted to leader and belongs to a crew, unassign old leader and update crew.leader_id
            if (w.getRole() == com.bellgado.logistics_ted.domain.WorkerRole.CREW_LEADER && w.getCrew() != null) {
                com.bellgado.logistics_ted.domain.Crew crew = w.getCrew();
                com.bellgado.logistics_ted.domain.Worker oldLeader = crew.getLeader();
                if (oldLeader != null && !oldLeader.getId().equals(w.getId())) {
                    oldLeader.setCrew(null);
                    workers.save(oldLeader);
                }
                crew.setLeader(w);
                crews.save(crew);
                houseStages.syncLeaderNameForCrew(crew.getId(), w.getName());
            }
            return ResponseEntity.ok(toDto(workers.save(w)));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Integer id) {
        if (!workers.existsById(id)) return ResponseEntity.notFound().build();
        workers.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private String validateBody(Map<String, Object> body, boolean requireName) {
        if (requireName) {
            Object name = body.get("name");
            if (name == null || name.toString().isBlank()) return "Worker name is required.";
        }
        if (body.get("lat") != null && body.get("lng") != null) {
            try {
                double lat = Double.parseDouble(body.get("lat").toString());
                double lng = Double.parseDouble(body.get("lng").toString());
                if (lat < -90 || lat > 90)   return "Latitude must be between -90 and 90.";
                if (lng < -180 || lng > 180) return "Longitude must be between -180 and 180.";
            } catch (NumberFormatException e) {
                return "Invalid coordinates.";
            }
        }
        return null;
    }

    private Worker applyBody(Worker w, Map<String, Object> body) {
        if (body.get("name")     != null) w.setName((String) body.get("name"));
        if (body.containsKey("location")) w.setLocation(body.getOrDefault("location", "").toString());
        if (body.get("lat")      != null) w.setLat(new java.math.BigDecimal(body.get("lat").toString()));
        if (body.get("lng")      != null) w.setLng(new java.math.BigDecimal(body.get("lng").toString()));
        if (body.containsKey("phone")) w.setPhone(body.get("phone") != null ? body.get("phone").toString() : null);
        if (body.containsKey("email")) w.setEmail(body.get("email") != null ? body.get("email").toString() : null);
        if (body.get("role") != null)
            w.setRole(com.bellgado.logistics_ted.domain.WorkerRole.valueOf(body.get("role").toString()));
        if (body.containsKey("stageOrders")) {
            Object so = body.get("stageOrders");
            if (so instanceof java.util.List<?> list) {
                w.setStageOrders(list.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(v -> ((Number) v).intValue())
                    .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new)));
            }
        }
        // House is derived from the worker's crew — never set directly on the worker.
        return w;
    }

    private Map<String, Object> toDto(Worker w) {
        return toDto(w, null, null, null);
    }

    private Map<String, Object> toDto(Worker w,
                                      Map<Integer, String> stageNamesPerCrew,
                                      Map<Integer, List<Map<String, Object>>> housesPerCrew,
                                      Map<Integer, String[]> allStageNames) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",       w.getId());
        m.put("name",     w.getName());
        m.put("location", w.getLocation());
        m.put("lat",      w.getLat());
        m.put("lng",      w.getLng());
        m.put("role",     w.getRole());
        m.put("phone",    w.getPhone());
        m.put("email",    w.getEmail());
        if (w.getCrew() != null) {
            m.put("crewId",   w.getCrew().getId());
            m.put("crewName", w.getCrew().getName());
            com.bellgado.logistics_ted.domain.House crewHouse = w.getCrew().getHouse();
            m.put("crewHouseId",   crewHouse != null ? crewHouse.getId()   : null);
            m.put("crewHouseName", crewHouse != null ? crewHouse.getName() : null);
            Worker mgr = w.getCrew().getManager();
            m.put("managerId",   mgr != null ? mgr.getId()   : null);
            m.put("managerName", mgr != null ? mgr.getName() : null);
            com.bellgado.logistics_ted.domain.Worker ldr = w.getCrew().getLeader();
            m.put("leaderId",   ldr != null ? ldr.getId()   : null);
            m.put("leaderName", ldr != null ? ldr.getName() : null);
            int cid = w.getCrew().getId();
            m.put("crewStages", stageNamesPerCrew != null
                ? stageNamesPerCrew.get(cid)
                : houseStages.findStageNamesForCrew(cid));
            List<Integer> orders = w.getStageOrders() != null ? w.getStageOrders() : List.of();
            m.put("stageOrders", orders);
            m.put("stageNamesBg", orders.stream().map(o -> { String[] s = allStageNames != null ? allStageNames.get(o) : null; return s != null ? s[0] : String.valueOf(o); }).toList());
            m.put("stageNamesEn", orders.stream().map(o -> { String[] s = allStageNames != null ? allStageNames.get(o) : null; return s != null && s[1] != null ? s[1] : (s != null ? s[0] : String.valueOf(o)); }).toList());
            List<Map<String, Object>> houses = housesPerCrew != null
                ? housesPerCrew.getOrDefault(cid, List.of())
                : houseStages.findAssignedHousesForCrew(cid).stream().map(row -> {
                    Map<String, Object> h = new LinkedHashMap<>();
                    h.put("houseId",   ((Number) row[0]).intValue());
                    h.put("houseName", row[1]);
                    return h;
                  }).toList();
            m.put("houses", houses);
            m.put("houseId",   houses.isEmpty() ? null : houses.get(0).get("houseId"));
            m.put("houseName", houses.isEmpty() ? null : houses.get(0).get("houseName"));
        } else {
            m.put("crewStages",  null);
            List<Integer> orders = w.getStageOrders() != null ? w.getStageOrders() : List.of();
            m.put("stageOrders", orders);
            m.put("stageNamesBg", orders.stream().map(o -> { String[] s = allStageNames != null ? allStageNames.get(o) : null; return s != null ? s[0] : String.valueOf(o); }).toList());
            m.put("stageNamesEn", orders.stream().map(o -> { String[] s = allStageNames != null ? allStageNames.get(o) : null; return s != null && s[1] != null ? s[1] : (s != null ? s[0] : String.valueOf(o)); }).toList());
            m.put("crewId",      null);
            m.put("crewName",    null);
            m.put("managerId",   null);
            m.put("managerName", null);
            m.put("leaderId",    null);
            m.put("leaderName",  null);
            m.put("houses",      List.of());
            m.put("houseId",     null);
            m.put("houseName",   null);
        }
        return m;
    }
}
