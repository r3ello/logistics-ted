package com.bellgado.logistics_ted.web;

import com.bellgado.logistics_ted.domain.Crew;
import com.bellgado.logistics_ted.domain.Worker;
import com.bellgado.logistics_ted.domain.WorkerRole;
import com.bellgado.logistics_ted.repository.CrewRepository;
import com.bellgado.logistics_ted.repository.HouseRepository;
import com.bellgado.logistics_ted.repository.HouseStageRepository;
import com.bellgado.logistics_ted.repository.WorkerRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/crews")
public class CrewController {

    private final CrewRepository        crews;
    private final WorkerRepository      workers;
    private final HouseRepository       houses;
    private final HouseStageRepository  houseStages;
    private final JdbcTemplate          jdbc;

    public CrewController(CrewRepository crews, WorkerRepository workers, HouseRepository houses,
                          HouseStageRepository houseStages, JdbcTemplate jdbc) {
        this.crews       = crews;
        this.workers     = workers;
        this.houses      = houses;
        this.houseStages = houseStages;
        this.jdbc        = jdbc;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        Map<Integer, List<Map<String, Object>>> housesPerCrew = new HashMap<>();
        for (Object[] row : houseStages.findAllAssignedHousesPerCrew()) {
            int crewId = ((Number) row[0]).intValue();
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("id",   ((Number) row[1]).intValue());
            h.put("name", row[2]);
            housesPerCrew.computeIfAbsent(crewId, k -> new ArrayList<>()).add(h);
        }
        return crews.findAll().stream().map(c -> toDto(c, housesPerCrew)).toList();
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<?> get(@PathVariable Integer id) {
        return crews.findById(id).map(c -> ResponseEntity.ok((Object) toDto(c, null)))
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/org-chart")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> orgChart() {
        List<Worker> managers = workers.findByRole(WorkerRole.CREW_MANAGER);
        return managers.stream().map(mgr -> {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("managerId",   mgr.getId());
            node.put("managerName", mgr.getName());
            List<Map<String, Object>> crewNodes = crews.findByManagerId(mgr.getId()).stream()
                .map(c -> toDto(c, null)).toList();
            node.put("crews", crewNodes);
            return node;
        }).toList();
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        if (body.get("leaderId") == null) return ResponseEntity.badRequest().body(Map.of("error", "leaderId is required."));
        Integer leaderId = Integer.parseInt(body.get("leaderId").toString());
        Worker leader = workers.findById(leaderId).orElse(null);
        if (leader == null) return ResponseEntity.badRequest().body(Map.of("error", "Leader not found."));
        if (leader.getRole() != WorkerRole.CREW_LEADER)
            return ResponseEntity.badRequest().body(Map.of("error", "Specified worker is not a CREW_LEADER."));

        Map<String, Object> bodyMut = new LinkedHashMap<>(body);
        if (bodyMut.get("name") == null || bodyMut.get("name").toString().isBlank())
            bodyMut.put("name", leader.getName());

        String err = validate(bodyMut);
        if (err != null) return ResponseEntity.badRequest().body(Map.of("error", err));

        Crew crew = crews.save(applyBody(new Crew(), bodyMut));
        crew.setLeader(leader);
        crews.save(crew);
        houseStages.syncLeaderNameForCrew(crew.getId(), leader.getName());
        leader.setCrew(crew);
        workers.save(leader);

        if (bodyMut.get("memberIds") instanceof List<?> memberIds) {
            for (Object mid : memberIds) {
                Worker member = workers.findById(Integer.parseInt(mid.toString())).orElse(null);
                if (member != null && member.getRole() == WorkerRole.CREW_MEMBER) {
                    member.setCrew(crew);
                    workers.save(member);
                }
            }
        }

        return ResponseEntity.ok(toDto(crew, null));
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable Integer id, @RequestBody Map<String, Object> body) {
        String err = validate(body);
        if (err != null) return ResponseEntity.badRequest().body(Map.of("error", err));
        return crews.findById(id).map(c -> {
            applyBody(c, body);
            Crew saved = crews.save(c);
            // sync all LEADER/MEMBER workers' stage assignments to match the crew's
            if (body.containsKey("stageOrders")) {
                workers.findByCrewId(id).stream()
                    .filter(w -> w.getRole() == WorkerRole.CREW_LEADER || w.getRole() == WorkerRole.CREW_MEMBER)
                    .forEach(w -> { w.setStageOrders(new ArrayList<>(saved.getStageOrders())); workers.save(w); });
            }
            return ResponseEntity.ok((Object) toDto(saved, null));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> delete(@PathVariable Integer id) {
        if (!crews.existsById(id)) return ResponseEntity.notFound().build();
        workers.findByCrewId(id).forEach(w -> { w.setCrew(null); workers.save(w); });
        crews.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/members/{workerId}")
    @Transactional
    public ResponseEntity<?> addMember(@PathVariable Integer id, @PathVariable Integer workerId) {
        Crew crew = crews.findById(id).orElse(null);
        if (crew == null) return ResponseEntity.notFound().build();
        Worker w = workers.findById(workerId).orElse(null);
        if (w == null) return ResponseEntity.notFound().build();
        if (w.getRole() == WorkerRole.CREW_MANAGER)
            return ResponseEntity.badRequest().body(Map.of("error", "Managers cannot be crew members."));
        if (w.getRole() == WorkerRole.CREW_LEADER) {
            Worker existing = crew.getLeader();
            if (existing != null && !existing.getId().equals(workerId)) {
                existing.setCrew(null);
                existing.setStageOrders(new ArrayList<>());
                workers.save(existing);
            }
            crew.setLeader(w);
            crews.save(crew);
            houseStages.syncLeaderNameForCrew(id, w.getName());
        }
        w.setCrew(crew);
        w.setStageOrders(new ArrayList<>(crew.getStageOrders()));
        workers.save(w);
        return ResponseEntity.ok(toDto(crew, null));
    }

    @DeleteMapping("/{id}/members/{workerId}")
    @Transactional
    public ResponseEntity<?> removeMember(@PathVariable Integer id, @PathVariable Integer workerId) {
        Crew crew = crews.findById(id).orElse(null);
        Worker w = workers.findById(workerId).orElse(null);
        if (w == null || w.getCrew() == null || !w.getCrew().getId().equals(id))
            return ResponseEntity.notFound().build();
        if (w.getRole() == WorkerRole.CREW_LEADER && crew != null &&
                crew.getLeader() != null && crew.getLeader().getId().equals(workerId)) {
            crew.setLeader(null);
            crews.save(crew);
            houseStages.syncLeaderNameForCrew(id, null);
        }
        w.setCrew(null);
        w.setStageOrders(new ArrayList<>());
        workers.save(w);
        return ResponseEntity.noContent().build();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String validate(Map<String, Object> body) {
        Object name = body.get("name");
        if (name == null || name.toString().isBlank()) return "Crew name is required.";
        if (body.get("managerId") != null) {
            Worker m = workers.findById(Integer.parseInt(body.get("managerId").toString())).orElse(null);
            if (m == null) return "Manager not found.";
            if (m.getRole() != WorkerRole.CREW_MANAGER) return "Selected worker is not a Crew Manager.";
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Crew applyBody(Crew c, Map<String, Object> body) {
        if (body.get("name") != null) c.setName(body.get("name").toString());
        if (body.containsKey("managerId")) {
            if (body.get("managerId") == null) c.setManager(null);
            else c.setManager(workers.findById(Integer.parseInt(body.get("managerId").toString())).orElse(null));
        }
        if (body.containsKey("houseId")) {
            if (body.get("houseId") == null) c.setHouse(null);
            else c.setHouse(houses.findById(Integer.parseInt(body.get("houseId").toString())).orElse(null));
        }
        // stageOrders — accepts list of integers
        if (body.containsKey("stageOrders")) {
            List<Integer> orders = new ArrayList<>();
            Object raw = body.get("stageOrders");
            if (raw instanceof List<?> list) {
                for (Object o : list) {
                    if (o != null) orders.add(Integer.parseInt(o.toString()));
                }
            }
            c.setStageOrders(orders);
        }
        if (body.containsKey("location"))
            c.setLocation(body.get("location") == null ? "" : body.get("location").toString());
        if (body.containsKey("lat"))
            c.setLat(body.get("lat") == null ? null : new java.math.BigDecimal(body.get("lat").toString()));
        if (body.containsKey("lng"))
            c.setLng(body.get("lng") == null ? null : new java.math.BigDecimal(body.get("lng").toString()));
        return c;
    }

    private Map<String, Object> toDto(Crew c, Map<Integer, List<Map<String, Object>>> housesPerCrew) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",   c.getId());
        m.put("name", c.getName());
        m.put("managerId",   c.getManager() != null ? c.getManager().getId()   : null);
        m.put("managerName", c.getManager() != null ? c.getManager().getName() : null);
        Worker leader = c.getLeader();
        m.put("leaderId",   leader != null ? leader.getId()   : null);
        m.put("leaderName", leader != null ? leader.getName() : null);

        List<Worker> members = workers.findByCrewId(c.getId());
        List<Map<String, Object>> memberList = members.stream()
            .filter(w -> w.getRole() == WorkerRole.CREW_MEMBER)
            .map(w -> { Map<String, Object> mm = new LinkedHashMap<>(); mm.put("id", w.getId()); mm.put("name", w.getName()); mm.put("stageOrders", w.getStageOrders()); return mm; })
            .toList();
        m.put("members",     memberList);
        m.put("memberCount", memberList.size());

        List<Map<String, Object>> assignedHouses = housesPerCrew != null
            ? housesPerCrew.getOrDefault(c.getId(), List.of())
            : houseStages.findAssignedHousesForCrew(c.getId()).stream().map(row -> {
                Map<String, Object> h = new LinkedHashMap<>(); h.put("id", row[0]); h.put("name", row[1]); return h;
              }).toList();
        m.put("assignedHouses", assignedHouses);

        // stage orders list + resolved names
        List<Integer> orders = c.getStageOrders() != null ? c.getStageOrders() : List.of();
        m.put("stageOrders", orders);
        if (!orders.isEmpty()) {
            String inClause = orders.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
            Map<Integer, String[]> stageMap = new HashMap<>();
            jdbc.query("SELECT stage_order, stage_name, stage_name_en FROM stage_type WHERE stage_order IN (" + inClause + ")",
                rs -> { stageMap.put(rs.getInt(1), new String[]{rs.getString(2), rs.getString(3)}); });
            m.put("stageNamesBg", orders.stream().map(o -> { String[] s = stageMap.get(o); return s != null ? s[0] : String.valueOf(o); }).toList());
            m.put("stageNamesEn", orders.stream().map(o -> { String[] s = stageMap.get(o); return s != null && s[1] != null ? s[1] : (s != null ? s[0] : String.valueOf(o)); }).toList());
        } else {
            m.put("stageNamesBg", List.of());
            m.put("stageNamesEn", List.of());
        }

        m.put("houseId",   c.getHouse() != null ? c.getHouse().getId()   : null);
        m.put("houseName", c.getHouse() != null ? c.getHouse().getName() : null);
        m.put("location",  c.getLocation() != null ? c.getLocation() : "");
        m.put("lat",       c.getLat());
        m.put("lng",       c.getLng());
        return m;
    }
}
