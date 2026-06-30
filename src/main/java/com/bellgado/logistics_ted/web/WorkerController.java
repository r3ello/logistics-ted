package com.bellgado.logistics_ted.web;

import com.bellgado.logistics_ted.domain.House;
import com.bellgado.logistics_ted.domain.Worker;
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
@RequestMapping("/api/workers")
public class WorkerController {

    private final WorkerRepository workers;
    private final HouseStageRepository houseStages;

    public WorkerController(WorkerRepository workers, HouseStageRepository houseStages) {
        this.workers = workers;
        this.houseStages = houseStages;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return workers.findAll().stream().map(this::toDto).toList();
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        String err = validateBody(body, true);
        if (err != null) return ResponseEntity.badRequest().body(Map.of("error", err));
        return ResponseEntity.ok(toDto(workers.save(applyBody(new Worker(), body))));
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable Integer id, @RequestBody Map<String, Object> body) {
        String err = validateBody(body, false);
        if (err != null) return ResponseEntity.badRequest().body(Map.of("error", err));
        return workers.findById(id)
            .map(w -> ResponseEntity.ok(toDto(workers.save(applyBody(w, body)))))
            .orElse(ResponseEntity.notFound().build());
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
        if (body.get("role") != null)
            w.setRole(com.bellgado.logistics_ted.domain.WorkerRole.valueOf(body.get("role").toString()));
        // managers have no trade; members and leaders do
        if (w.getRole() == com.bellgado.logistics_ted.domain.WorkerRole.CREW_MANAGER) {
            w.setTrade(null);
        } else if (body.containsKey("trade")) {
            w.setTrade(body.get("trade") != null ? body.get("trade").toString() : null);
        }
        // House is derived from the worker's crew — never set directly on the worker.
        return w;
    }

    private Map<String, Object> toDto(Worker w) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",       w.getId());
        m.put("name",     w.getName());
        m.put("location", w.getLocation());
        m.put("lat",      w.getLat());
        m.put("lng",      w.getLng());
        m.put("trade",    w.getTrade());
        m.put("role",     w.getRole());
        if (w.getCrew() != null) {
            m.put("crewId",   w.getCrew().getId());
            m.put("crewName", w.getCrew().getName());
            House crewHouse = w.getCrew().getHouse();
            m.put("crewHouseId",   crewHouse != null ? crewHouse.getId()   : null);
            m.put("crewHouseName", crewHouse != null ? crewHouse.getName() : null);
            Worker mgr = w.getCrew().getManager();
            m.put("managerId",   mgr != null ? mgr.getId()   : null);
            m.put("managerName", mgr != null ? mgr.getName() : null);
            workers.findByCrewIdAndRole(w.getCrew().getId(), com.bellgado.logistics_ted.domain.WorkerRole.CREW_LEADER)
                .stream().findFirst().ifPresentOrElse(
                    ldr -> { m.put("leaderId", ldr.getId()); m.put("leaderName", ldr.getName()); },
                    ()  -> { m.put("leaderId", null);        m.put("leaderName", null); }
                );
            m.put("crewStages", houseStages.findStageNamesForCrew(w.getCrew().getId()));
        } else {
            m.put("crewStages",  null);
            m.put("crewId",      null);
            m.put("crewName",    null);
            m.put("managerId",   null);
            m.put("managerName", null);
            m.put("leaderId",    null);
            m.put("leaderName",  null);
        }
        // houseId/houseName come from the crew, not the worker directly
        House crewHouseForId = w.getCrew() != null ? w.getCrew().getHouse() : null;
        m.put("houseId",   crewHouseForId != null ? crewHouseForId.getId()   : null);
        m.put("houseName", crewHouseForId != null ? crewHouseForId.getName() : null);
        return m;
    }
}
