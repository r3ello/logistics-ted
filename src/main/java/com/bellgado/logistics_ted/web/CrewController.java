package com.bellgado.logistics_ted.web;

import com.bellgado.logistics_ted.domain.Crew;
import com.bellgado.logistics_ted.domain.House;
import com.bellgado.logistics_ted.domain.Worker;
import com.bellgado.logistics_ted.domain.WorkerRole;
import com.bellgado.logistics_ted.repository.CrewRepository;
import com.bellgado.logistics_ted.repository.HouseRepository;
import com.bellgado.logistics_ted.repository.HouseStageRepository;
import com.bellgado.logistics_ted.repository.WorkerRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
@RequestMapping("/api/crews")
public class CrewController {

    private final CrewRepository        crews;
    private final WorkerRepository      workers;
    private final HouseRepository       houses;
    private final HouseStageRepository  houseStages;

    public CrewController(CrewRepository crews, WorkerRepository workers, HouseRepository houses, HouseStageRepository houseStages) {
        this.crews       = crews;
        this.workers     = workers;
        this.houses      = houses;
        this.houseStages = houseStages;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return crews.findAll().stream().map(this::toDto).toList();
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<?> get(@PathVariable Integer id) {
        return crews.findById(id).map(c -> ResponseEntity.ok((Object) toDto(c)))
            .orElse(ResponseEntity.notFound().build());
    }

    /** Org chart: managers -> their crews -> leader + members. */
    @GetMapping("/org-chart")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> orgChart() {
        List<Worker> managers = workers.findByRole(WorkerRole.CREW_MANAGER);
        return managers.stream().map(mgr -> {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("managerId",   mgr.getId());
            node.put("managerName", mgr.getName());
            List<Map<String, Object>> crewNodes = crews.findByManagerId(mgr.getId()).stream()
                .map(this::toDto).toList();
            node.put("crews", crewNodes);
            return node;
        }).toList();
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        String err = validate(body);
        if (err != null) return ResponseEntity.badRequest().body(Map.of("error", err));
        return ResponseEntity.ok(toDto(crews.save(applyBody(new Crew(), body))));
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable Integer id, @RequestBody Map<String, Object> body) {
        String err = validate(body);
        if (err != null) return ResponseEntity.badRequest().body(Map.of("error", err));
        return crews.findById(id)
            .map(c -> ResponseEntity.ok((Object) toDto(crews.save(applyBody(c, body)))))
            .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> delete(@PathVariable Integer id) {
        if (!crews.existsById(id)) return ResponseEntity.notFound().build();
        // unassign all workers from this crew first
        workers.findByCrewId(id).forEach(w -> { w.setCrew(null); workers.save(w); });
        crews.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    /** Assign a worker (leader or member) to this crew. */
    @PostMapping("/{id}/members/{workerId}")
    @Transactional
    public ResponseEntity<?> addMember(@PathVariable Integer id, @PathVariable Integer workerId) {
        Crew crew = crews.findById(id).orElse(null);
        if (crew == null) return ResponseEntity.notFound().build();
        Worker w = workers.findById(workerId).orElse(null);
        if (w == null) return ResponseEntity.notFound().build();
        if (w.getRole() == WorkerRole.CREW_MANAGER)
            return ResponseEntity.badRequest().body(Map.of("error", "Managers cannot be crew members. Assign them as the crew manager instead."));
        // one leader per crew
        if (w.getRole() == WorkerRole.CREW_LEADER) {
            boolean hasLeader = workers.findByCrewIdAndRole(id, WorkerRole.CREW_LEADER).stream()
                .anyMatch(x -> !x.getId().equals(workerId));
            if (hasLeader)
                return ResponseEntity.badRequest().body(Map.of("error", "This crew already has a leader."));
        }
        w.setCrew(crew);
        workers.save(w);
        return ResponseEntity.ok(toDto(crew));
    }

    /** Remove a worker from this crew. */
    @DeleteMapping("/{id}/members/{workerId}")
    @Transactional
    public ResponseEntity<?> removeMember(@PathVariable Integer id, @PathVariable Integer workerId) {
        Worker w = workers.findById(workerId).orElse(null);
        if (w == null || w.getCrew() == null || !w.getCrew().getId().equals(id))
            return ResponseEntity.notFound().build();
        w.setCrew(null);
        workers.save(w);
        return ResponseEntity.noContent().build();
    }

    // ── helpers ──────────────────────────────────────────────────────────────
    private String validate(Map<String, Object> body) {
        Object name = body.get("name");
        if (name == null || name.toString().isBlank()) return "Crew name is required.";
        if (body.get("managerId") != null) {
            Integer mid = Integer.parseInt(body.get("managerId").toString());
            Worker m = workers.findById(mid).orElse(null);
            if (m == null) return "Manager not found.";
            if (m.getRole() != WorkerRole.CREW_MANAGER) return "Selected worker is not a Crew Manager.";
        }
        return null;
    }

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
        if (body.containsKey("stageOrder")) {
            c.setStageOrder(body.get("stageOrder") == null ? null : Integer.parseInt(body.get("stageOrder").toString()));
        }
        return c;
    }

    private Map<String, Object> toDto(Crew c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",   c.getId());
        m.put("name", c.getName());
        if (c.getManager() != null) {
            m.put("managerId",   c.getManager().getId());
            m.put("managerName", c.getManager().getName());
        } else {
            m.put("managerId", null);
            m.put("managerName", null);
        }
        List<Worker> members = workers.findByCrewId(c.getId());
        Worker leader = members.stream().filter(w -> w.getRole() == WorkerRole.CREW_LEADER).findFirst().orElse(null);
        if (leader != null) {
            m.put("leaderId",   leader.getId());
            m.put("leaderName", leader.getName());
        } else {
            m.put("leaderId", null);
            m.put("leaderName", null);
        }
        List<Map<String, Object>> memberList = members.stream()
            .filter(w -> w.getRole() == WorkerRole.CREW_MEMBER)
            .map(w -> {
                Map<String, Object> mm = new LinkedHashMap<>();
                mm.put("id",    w.getId());
                mm.put("name",  w.getName());
                mm.put("trade", w.getTrade());
                return mm;
            }).toList();
        m.put("members", memberList);
        m.put("memberCount", memberList.size());
        List<Map<String, Object>> assignedHouses = houseStages.findAssignedHousesForCrew(c.getId())
            .stream().map(row -> {
                Map<String, Object> h = new LinkedHashMap<>();
                h.put("id",   row[0]);
                h.put("name", row[1]);
                return h;
            }).toList();
        m.put("assignedHouses", assignedHouses);
        m.put("stageNames", houseStages.findStageNamesForCrew(c.getId()));
        m.put("stageOrder", c.getStageOrder());
        if (c.getStageOrder() != null) {
            String stageName = houseStages.findStageNameByOrder(c.getStageOrder());
            m.put("stageName", stageName);
        } else {
            m.put("stageName", null);
        }
        // keep legacy houseId/houseName for map features
        if (c.getHouse() != null) {
            m.put("houseId",   c.getHouse().getId());
            m.put("houseName", c.getHouse().getName());
        } else {
            m.put("houseId",   null);
            m.put("houseName", null);
        }
        return m;
    }
}
