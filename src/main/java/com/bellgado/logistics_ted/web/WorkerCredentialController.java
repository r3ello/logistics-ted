package com.bellgado.logistics_ted.web;

import com.bellgado.logistics_ted.domain.Worker;
import com.bellgado.logistics_ted.domain.WorkerRole;
import com.bellgado.logistics_ted.repository.CrewRepository;
import com.bellgado.logistics_ted.repository.HouseRepository;
import com.bellgado.logistics_ted.repository.HouseStageRepository;
import com.bellgado.logistics_ted.repository.WorkerRepository;
import com.bellgado.logistics_ted.security.JwtService;
import com.bellgado.logistics_ted.service.AuditLogService;
import com.bellgado.logistics_ted.web.logging.RequestCorrelationFilter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
public class WorkerCredentialController {

    private static final Logger log = LoggerFactory.getLogger(WorkerCredentialController.class);

    private final WorkerRepository     workers;
    private final CrewRepository       crews;
    private final HouseStageRepository houseStages;
    private final HouseRepository      houses;
    private final PasswordEncoder      encoder;
    private final JwtService           jwt;
    private final AuditLogService      audit;

    public WorkerCredentialController(WorkerRepository workers, CrewRepository crews,
                                      HouseStageRepository houseStages, HouseRepository houses,
                                      PasswordEncoder encoder, JwtService jwt,
                                      AuditLogService audit) {
        this.workers     = workers;
        this.crews       = crews;
        this.houseStages = houseStages;
        this.houses      = houses;
        this.encoder     = encoder;
        this.jwt         = jwt;
        this.audit       = audit;
    }

    /** List all workers (grouped by crew) with credential status — no password exposed. */
    @GetMapping("/api/workers/credentials")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return crews.findAll().stream()
            .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
            .map(crew -> {
                Map<String, Object> g = new LinkedHashMap<>();
                g.put("crewId",    crew.getId());
                g.put("crewName",  crew.getName());
                g.put("stageOrders", crew.getStageOrders());
                g.put("stageName", null);
                List<Map<String, Object>> members = workers.findByCrewId(crew.getId()).stream()
                    .map(w -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id",          w.getId());
                        m.put("name",        w.getName());
                        m.put("role",        w.getRole().name());
                        m.put("username",    w.getUsername());
                        m.put("hasPassword", w.getPasswordHash() != null);
                        m.put("passwordPlain", w.getPasswordPlain());
                        List<Map<String, Object>> houses = houseStages.findAssignedHousesForCrew(crew.getId())
                            .stream().map(row -> {
                                Map<String, Object> h = new LinkedHashMap<>();
                                h.put("houseId",   ((Number) row[0]).intValue());
                                h.put("houseName", row[1]);
                                return h;
                            }).toList();
                        m.put("houses", houses);
                        return m;
                    }).toList();
                g.put("members", members);
                return g;
            }).toList();
    }

    /** Set or update username + password for a worker. */
    @PutMapping("/api/workers/{id}/credentials")
    @Transactional
    public ResponseEntity<?> setCredentials(@PathVariable Integer id,
                                            @RequestBody Map<String, Object> body) {
        Worker w = workers.findById(id).orElse(null);
        if (w == null) return ResponseEntity.notFound().build();

        String username = body.get("username") != null ? body.get("username").toString().trim() : null;
        String password = body.get("password") != null ? body.get("password").toString() : null;

        if (username == null || username.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "Username is required."));
        if (username.length() < 3)
            return ResponseEntity.badRequest().body(Map.of("error", "Username must be at least 3 characters."));

        // check uniqueness (excluding self)
        workers.findByUsername(username).ifPresent(existing -> {
            if (!existing.getId().equals(id))
                throw new IllegalArgumentException("Username already taken.");
        });

        w.setUsername(username);
        if (password != null && !password.isBlank()) {
            if (password.length() < 4)
                throw new IllegalArgumentException("Password must be at least 4 characters.");
            w.setPasswordHash(encoder.encode(password));
        }
        workers.save(w);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("id",          w.getId());
        res.put("username",    w.getUsername());
        res.put("hasPassword", w.getPasswordHash() != null);
        return ResponseEntity.ok(res);
    }

    /** Reset password only — username stays unchanged. */
    @PutMapping("/api/workers/{id}/password")
    @Transactional
    public ResponseEntity<?> resetPassword(@PathVariable Integer id,
                                           @RequestBody Map<String, Object> body) {
        Worker w = workers.findById(id).orElse(null);
        if (w == null) return ResponseEntity.notFound().build();

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

    /** Worker login — returns JWT on success. */
    @PostMapping("/api/worker-login")
    @Transactional(readOnly = true)
    public ResponseEntity<?> login(@RequestBody Map<String, Object> body) {
        String username = body.get("username") != null ? body.get("username").toString().trim() : null;
        String password = body.get("password") != null ? body.get("password").toString() : null;

        if (username == null || password == null)
            return ResponseEntity.badRequest().body(Map.of("error", "Username and password required."));

        Worker w = workers.findByUsername(username).orElse(null);
        if (w == null || w.getPasswordHash() == null || !encoder.matches(password, w.getPasswordHash())) {
            auditLoginFailure(username, "bad_credentials", 401);
            return ResponseEntity.status(401).body(Map.of("error", "Invalid username or password."));
        }

        // validate that the worker's crew is assigned to the scanned house
        String scannedToken = body.get("houseToken") != null ? body.get("houseToken").toString() : null;
        if (scannedToken != null && !scannedToken.isBlank()) {
            var house = houses.findByCheckinToken(scannedToken).orElse(null);
            if (house == null) {
                auditLoginFailure(username, "invalid_checkin_token", 403);
                return ResponseEntity.status(403).body(Map.of("error", "Invalid check-in point."));
            }
            if (w.getCrew() != null) {
                List<Object[]> assigned = houseStages.findAssignedHousesForCrew(w.getCrew().getId());
                boolean assignedToHouse = assigned.stream()
                    .anyMatch(row -> house.getId().equals(((Number) row[0]).intValue()));
                if (!assignedToHouse) {
                    auditLoginFailure(username, "house_mismatch", 403);
                    return ResponseEntity.status(403).body(Map.of("error",
                        "Не сте назначен на тази обект. / You are not assigned to this house."));
                }
            }
        }

        auditLoginSuccess(w);
        var issued = jwt.issue(w.getId(), w.getUsername(), "worker");

        // resolve house via crew
        String houseToken = null;
        String houseName  = null;
        if (w.getCrew() != null && w.getCrew().getHouse() != null) {
            var house = w.getCrew().getHouse();
            houseToken = house.getCheckinToken() != null ? house.getCheckinToken().toString() : null;
            houseName  = house.getName();
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("token",      issued.token());
        res.put("workerId",   w.getId());
        res.put("workerName", w.getName());
        res.put("houseToken", houseToken);
        res.put("houseName",  houseName);
        return ResponseEntity.ok(res);
    }

    // Audit failures must never break the login flow — swallow and warn.
    private void auditLoginSuccess(Worker w) {
        try {
            audit.recordLoginSuccess(
                new AuditLogService.Actor("worker", null, w.getId(), null, w.getUsername(), "worker"),
                "/api/worker-login",
                MDC.get(RequestCorrelationFilter.CLIENT_IP), MDC.get(RequestCorrelationFilter.REQUEST_ID));
        } catch (RuntimeException ex) {
            log.warn("audit: failed to record worker-login success", ex);
        }
    }

    private void auditLoginFailure(String attemptedUsername, String reason, int status) {
        try {
            audit.recordLoginFailure("/api/worker-login", attemptedUsername, reason, status,
                MDC.get(RequestCorrelationFilter.CLIENT_IP), MDC.get(RequestCorrelationFilter.REQUEST_ID));
        } catch (RuntimeException ex) {
            log.warn("audit: failed to record worker-login failure", ex);
        }
    }
}
