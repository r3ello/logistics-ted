package com.bellgado.logistics_ted.web;

import com.bellgado.logistics_ted.domain.Crew;
import com.bellgado.logistics_ted.domain.Worker;
import com.bellgado.logistics_ted.domain.WorkerRole;
import com.bellgado.logistics_ted.repository.CrewRepository;
import com.bellgado.logistics_ted.repository.WorkerRepository;
import com.bellgado.logistics_ted.config.WorkerCredentialSeeder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * Crew-leader scoped crew & worker management. All endpoints are restricted to CREW_LEADER
 * and enforce that the caller can only touch their own crew and its members.
 */
@RestController
@RequestMapping("/api/my/crew")
@PreAuthorize("hasRole('CREW_LEADER')")
public class MyCrewController {

    private final WorkerRepository      workers;
    private final CrewRepository        crews;
    private final PasswordEncoder       encoder;
    private final WorkerCredentialSeeder seeder;

    public MyCrewController(WorkerRepository workers, CrewRepository crews,
                            PasswordEncoder encoder, WorkerCredentialSeeder seeder) {
        this.workers = workers;
        this.crews   = crews;
        this.encoder = encoder;
        this.seeder  = seeder;
    }

    // ── Crew info ─────────────────────────────────────────────────────────────

    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<?> getMyCrew() {
        Worker me = currentWorker();
        if (me == null || me.getCrew() == null)
            return ResponseEntity.status(403).body(Map.of("error", "No crew assigned."));
        return ResponseEntity.ok(crewDto(me.getCrew()));
    }

    @PutMapping
    @Transactional
    public ResponseEntity<?> updateCrew(@RequestBody Map<String, Object> body) {
        Worker me = currentWorker();
        if (me == null || me.getCrew() == null)
            return ResponseEntity.status(403).body(Map.of("error", "No crew assigned."));
        Crew crew = me.getCrew();
        if (body.containsKey("name") && body.get("name") != null) {
            String name = body.get("name").toString().trim();
            if (!name.isEmpty()) crew.setName(name);
        }
        return ResponseEntity.ok(crewDto(crews.save(crew)));
    }

    // ── Members ───────────────────────────────────────────────────────────────

    @GetMapping("/members")
    @Transactional(readOnly = true)
    public ResponseEntity<?> listMembers() {
        Worker me = currentWorker();
        if (me == null || me.getCrew() == null)
            return ResponseEntity.status(403).body(Map.of("error", "No crew assigned."));
        List<Map<String, Object>> members = workers.findByCrewId(me.getCrew().getId())
            .stream().map(this::workerDto).toList();
        return ResponseEntity.ok(members);
    }

    @PostMapping("/members")
    @Transactional
    public ResponseEntity<?> addMember(@RequestBody Map<String, Object> body) {
        Worker me = currentWorker();
        if (me == null || me.getCrew() == null)
            return ResponseEntity.status(403).body(Map.of("error", "No crew assigned."));

        String name = body.get("name") != null ? body.get("name").toString().trim() : "";
        if (name.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "Name is required."));

        Worker w = new Worker();
        w.setName(name);
        w.setLocation(body.get("location") != null ? body.get("location").toString().trim() : "");
        w.setPhone(body.get("phone") != null ? body.get("phone").toString().trim() : null);
        w.setEmail(body.get("email") != null ? body.get("email").toString().trim() : null);
        w.setRole(WorkerRole.CREW_MEMBER);
        Crew crew = me.getCrew();
        w.setCrew(crew);
        // Always inherit crew's stages — crew leader cannot override per-member
        List<Integer> crewStages = crew.getStageOrders();
        w.setStageOrders(crewStages != null ? new java.util.ArrayList<>(crewStages) : new java.util.ArrayList<>());

        // auto-generate credentials from worker name
        Worker saved = workers.save(w);
        seeder.assignCredentials(saved);
        return ResponseEntity.ok(workerDto(workers.save(saved)));
    }

    @PutMapping("/members/{workerId}")
    @Transactional
    public ResponseEntity<?> updateMember(@PathVariable Integer workerId,
                                          @RequestBody Map<String, Object> body) {
        Worker me = currentWorker();
        if (me == null || me.getCrew() == null)
            return ResponseEntity.status(403).body(Map.of("error", "No crew assigned."));

        Worker w = workers.findById(workerId).orElse(null);
        if (w == null || !me.getCrew().getId().equals(w.getCrew() != null ? w.getCrew().getId() : null))
            return ResponseEntity.status(403).body(Map.of("error", "Worker not in your crew."));

        if (body.containsKey("name") && body.get("name") != null) w.setName(body.get("name").toString().trim());
        if (body.containsKey("phone"))    w.setPhone(body.get("phone") != null ? body.get("phone").toString().trim() : null);
        if (body.containsKey("email"))    w.setEmail(body.get("email") != null ? body.get("email").toString().trim() : null);
        if (body.containsKey("location") && body.get("location") != null) w.setLocation(body.get("location").toString().trim());
        applyStageOrders(w, body);
        return ResponseEntity.ok(workerDto(workers.save(w)));
    }

    @DeleteMapping("/members/{workerId}")
    @Transactional
    public ResponseEntity<?> removeMember(@PathVariable Integer workerId) {
        Worker me = currentWorker();
        if (me == null || me.getCrew() == null)
            return ResponseEntity.status(403).body(Map.of("error", "No crew assigned."));
        if (me.getId().equals(workerId))
            return ResponseEntity.badRequest().body(Map.of("error", "Cannot remove yourself from crew."));

        Worker w = workers.findById(workerId).orElse(null);
        if (w == null || !me.getCrew().getId().equals(w.getCrew() != null ? w.getCrew().getId() : null))
            return ResponseEntity.status(403).body(Map.of("error", "Worker not in your crew."));

        w.setCrew(null);
        workers.save(w);
        return ResponseEntity.noContent().build();
    }

    // ── Credentials ───────────────────────────────────────────────────────────

    @GetMapping("/members/credentials")
    @Transactional(readOnly = true)
    public ResponseEntity<?> listCredentials() {
        Worker me = currentWorker();
        if (me == null || me.getCrew() == null)
            return ResponseEntity.status(403).body(Map.of("error", "No crew assigned."));
        List<Map<String, Object>> members = workers.findByCrewId(me.getCrew().getId())
            .stream().map(this::credentialDto).toList();
        return ResponseEntity.ok(members);
    }

    @PutMapping("/members/{workerId}/credentials")
    @Transactional
    public ResponseEntity<?> setCredentials(@PathVariable Integer workerId,
                                            @RequestBody Map<String, Object> body) {
        Worker me = currentWorker();
        Worker w  = workerInMyCrew(me, workerId);
        if (w == null) return ResponseEntity.status(403).body(Map.of("error", "Worker not in your crew."));

        String username = body.get("username") != null ? body.get("username").toString().trim() : null;
        String password = body.get("password") != null ? body.get("password").toString() : null;
        if (username == null || username.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "Username is required."));
        if (username.length() < 3)
            return ResponseEntity.badRequest().body(Map.of("error", "Username must be at least 3 characters."));

        var existing = workers.findByUsername(username);
        if (existing.isPresent() && !existing.get().getId().equals(workerId))
            return ResponseEntity.badRequest().body(Map.of("error", "Username already taken."));

        w.setUsername(username);
        if (password != null && !password.isBlank()) {
            if (password.length() < 4)
                return ResponseEntity.badRequest().body(Map.of("error", "Password must be at least 4 characters."));
            w.setPasswordHash(encoder.encode(password));
            w.setPasswordPlain(password);
        }
        workers.save(w);
        return ResponseEntity.ok(credentialDto(w));
    }

    @PutMapping("/members/{workerId}/password")
    @Transactional
    public ResponseEntity<?> resetPassword(@PathVariable Integer workerId,
                                           @RequestBody Map<String, Object> body) {
        Worker me = currentWorker();
        Worker w  = workerInMyCrew(me, workerId);
        if (w == null) return ResponseEntity.status(403).body(Map.of("error", "Worker not in your crew."));

        String password = body.get("password") != null ? body.get("password").toString() : null;
        if (password == null || password.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "Password is required."));
        if (password.length() < 4)
            return ResponseEntity.badRequest().body(Map.of("error", "Password must be at least 4 characters."));

        w.setPasswordHash(encoder.encode(password));
        w.setPasswordPlain(password);
        workers.save(w);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Worker currentWorker() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return null;
        return workers.findByUsername(auth.getName()).orElse(null);
    }

    private Worker workerInMyCrew(Worker me, Integer workerId) {
        if (me == null || me.getCrew() == null) return null;
        Worker w = workers.findById(workerId).orElse(null);
        if (w == null) return null;
        return me.getCrew().getId().equals(w.getCrew() != null ? w.getCrew().getId() : null) ? w : null;
    }

    private Map<String, Object> crewDto(Crew c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",      c.getId());
        m.put("name",    c.getName());
        m.put("leaderId",   c.getLeader() != null ? c.getLeader().getId()   : null);
        m.put("leaderName", c.getLeader() != null ? c.getLeader().getName() : null);
        m.put("houseId",    c.getHouse() != null ? c.getHouse().getId()   : null);
        m.put("houseName",  c.getHouse() != null ? c.getHouse().getName() : null);
        m.put("stageOrders", c.getStageOrders() != null ? c.getStageOrders() : List.of());
        m.put("members", workers.findByCrewId(c.getId()).stream().map(this::workerDto).toList());
        return m;
    }

    private Map<String, Object> workerDto(Worker w) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",          w.getId());
        m.put("name",        w.getName());
        m.put("role",        w.getRole().name());
        m.put("phone",       w.getPhone());
        m.put("email",       w.getEmail());
        m.put("location",    w.getLocation());
        m.put("username",    w.getUsername());
        m.put("hasPassword", w.getPasswordHash() != null);
        m.put("stageOrders", w.getStageOrders());
        return m;
    }

    private static void applyStageOrders(Worker w, Map<String, Object> body) {
        if (!body.containsKey("stageOrders")) return;
        List<Integer> orders = new java.util.ArrayList<>();
        Object raw = body.get("stageOrders");
        if (raw instanceof List<?> list) {
            for (Object o : list) { if (o != null) orders.add(Integer.parseInt(o.toString())); }
        }
        w.setStageOrders(orders);
    }

    private Map<String, Object> credentialDto(Worker w) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",            w.getId());
        m.put("name",          w.getName());
        m.put("role",          w.getRole().name());
        m.put("username",      w.getUsername());
        m.put("hasPassword",   w.getPasswordHash() != null);
        m.put("passwordPlain", w.getPasswordPlain());
        return m;
    }
}
