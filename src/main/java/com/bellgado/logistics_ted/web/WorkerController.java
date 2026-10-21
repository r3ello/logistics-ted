package com.bellgado.logistics_ted.web;

import com.bellgado.logistics_ted.config.WorkerCredentialSeeder;
import com.bellgado.logistics_ted.domain.Worker;
import com.bellgado.logistics_ted.repository.HouseStageRepository;
import com.bellgado.logistics_ted.repository.WorkerRepository;
import com.bellgado.logistics_ted.service.CrewMembershipService;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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
    private final HouseStageRepository    houseStages;
    private final WorkerCredentialSeeder  credentialSeeder;
    private final JdbcTemplate            jdbc;
    private final CrewMembershipService   membership;

    public WorkerController(WorkerRepository workers,
                            HouseStageRepository houseStages, WorkerCredentialSeeder credentialSeeder,
                            JdbcTemplate jdbc, CrewMembershipService membership) {
        this.workers          = workers;
        this.houseStages      = houseStages;
        this.credentialSeeder = credentialSeeder;
        this.jdbc             = jdbc;
        this.membership       = membership;
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
                Worker displaced = membership.replaceLeader(w.getCrew(), w);
                if (displaced != null) {
                    displaced.setCrew(null);
                    workers.save(displaced);
                }
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
        // Checked here so a bad value is a 400, not a 500 from valueOf inside applyBody.
        if (body.get("role") != null && !isEnumName(com.bellgado.logistics_ted.domain.WorkerRole.class, body.get("role")))
            return "Invalid role.";
        Object type = body.get("employmentType");
        if (type != null && !type.toString().isBlank()
                && !isEnumName(com.bellgado.logistics_ted.domain.EmploymentType.class, type))
            return "Invalid employment type.";
        if (body.get("hireDate") != null && !body.get("hireDate").toString().isBlank()) {
            try {
                java.time.LocalDate.parse(body.get("hireDate").toString().trim());
            } catch (java.time.format.DateTimeParseException e) {
                return "Invalid hire date (expected YYYY-MM-DD).";
            }
        }
        return null;
    }

    private static <E extends Enum<E>> boolean isEnumName(Class<E> type, Object value) {
        try {
            Enum.valueOf(type, value.toString());
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
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
        // Staff-sheet fields (Flyway V21).
        if (body.containsKey("externalId")) { String eid = blankToNull(body.get("externalId")); if (eid != null) w.setExternalId(eid); }
        if (body.containsKey("employmentType")) {
            Object t = body.get("employmentType");
            w.setEmploymentType(t == null || t.toString().isBlank() ? null
                : com.bellgado.logistics_ted.domain.EmploymentType.valueOf(t.toString()));
        }
        if (body.containsKey("company"))        w.setCompany(blankToNull(body.get("company")));
        if (body.containsKey("photoUrl"))       w.setPhotoUrl(blankToNull(body.get("photoUrl")));
        if (body.containsKey("hireDate")) {
            String d = blankToNull(body.get("hireDate"));
            w.setHireDate(d == null ? null : java.time.LocalDate.parse(d));
        }
        if (body.containsKey("clothingSize"))   w.setClothingSize(blankToNull(body.get("clothingSize")));
        if (body.containsKey("clothingIssued")) w.setClothingIssued(blankToNull(body.get("clothingIssued")));
        // Personal data: only an admin may see or change it; a non-admin body carrying it is ignored.
        if (body.containsKey("idCardNumber") && isAdmin()) w.setIdCardNumber(blankToNull(body.get("idCardNumber")));
        // House is derived from the worker's crew — never set directly on the worker.
        return w;
    }

    private static String blankToNull(Object v) {
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static boolean isAdmin() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null && a.getAuthorities().stream().anyMatch(g -> "ROLE_ADMIN".equals(g.getAuthority()));
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
        m.put("externalId",     w.getExternalId());
        m.put("employmentType", w.getEmploymentType());
        m.put("company",        w.getCompany());
        m.put("photoUrl",       w.getPhotoUrl());
        m.put("hireDate",       w.getHireDate() != null ? w.getHireDate().toString() : null);
        m.put("clothingSize",   w.getClothingSize());
        m.put("clothingIssued", w.getClothingIssued());
        if (isAdmin()) m.put("idCardNumber", w.getIdCardNumber());
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
