package com.bellgado.logistics_ted.web;

import com.bellgado.logistics_ted.domain.House;
import com.bellgado.logistics_ted.domain.WorkSession;
import com.bellgado.logistics_ted.domain.Worker;
import com.bellgado.logistics_ted.repository.HouseRepository;
import com.bellgado.logistics_ted.repository.WorkSessionRepository;
import com.bellgado.logistics_ted.repository.WorkerRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;

@RestController
public class AttendanceController {

    private final HouseRepository       houses;
    private final WorkerRepository      workers;
    private final WorkSessionRepository sessions;

    public AttendanceController(HouseRepository houses,
                                WorkerRepository workers,
                                WorkSessionRepository sessions) {
        this.houses   = houses;
        this.workers  = workers;
        this.sessions = sessions;
    }

    // ── PUBLIC: checkin page data ────────────────────────────────────────────

    /** Return house info + crew workers for the checkin page. */
    @GetMapping("/api/public/checkin/{token}")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getCheckinInfo(@PathVariable String token) {
        House house = houses.findByCheckinToken(token).orElse(null);
        if (house == null) return ResponseEntity.notFound().build();

        // Find workers assigned to the crew working this house (JOIN FETCH avoids lazy-init)
        List<Map<String, Object>> workerList = new ArrayList<>();
        workers.findByHouseId(house.getId()).forEach(w -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id",   w.getId());
                m.put("name", w.getName());
                m.put("role", w.getRole().name());
                workerList.add(m);
            });

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("houseId",   house.getId());
        res.put("houseName", house.getName());
        res.put("lat",       house.getLat());
        res.put("lng",       house.getLng());
        res.put("workers",   workerList);
        return ResponseEntity.ok(res);
    }

    /** Get today's session status for a specific worker at this house. */
    @GetMapping("/api/public/checkin/{token}/worker/{workerId}/today")
    public ResponseEntity<?> getTodayStatus(@PathVariable String token,
                                             @PathVariable Integer workerId) {
        House house = houses.findByCheckinToken(token).orElse(null);
        if (house == null) return ResponseEntity.notFound().build();

        Optional<WorkSession> session = sessions.findTodaySession(workerId, house.getId(), LocalDate.now());
        Map<String, Object> res = new LinkedHashMap<>();
        if (session.isEmpty()) {
            res.put("status", "NONE");
        } else {
            WorkSession s = session.get();
            res.put("status",        s.getCheckedOutAt() == null ? "CHECKED_IN" : "CHECKED_OUT");
            res.put("sessionId",     s.getId());
            res.put("checkedInAt",   s.getCheckedInAt().toString());
            res.put("checkedOutAt",  s.getCheckedOutAt() != null ? s.getCheckedOutAt().toString() : null);
            if (s.getCheckedOutAt() != null) {
                long mins = ChronoUnit.MINUTES.between(s.getCheckedInAt(), s.getCheckedOutAt());
                res.put("durationMinutes", mins);
            }
        }
        return ResponseEntity.ok(res);
    }

    /** Check in a worker. */
    @PostMapping("/api/public/checkin/{token}/session")
    @Transactional
    public ResponseEntity<?> checkIn(@PathVariable String token,
                                      @RequestBody Map<String, Object> body) {
        House house = houses.findByCheckinToken(token).orElse(null);
        if (house == null) return ResponseEntity.notFound().build();

        Integer workerId = (Integer) body.get("workerId");
        String  deviceId = (String)  body.get("deviceId");
        Double  lat      = toDouble(body.get("lat"));
        Double  lng      = toDouble(body.get("lng"));

        if (workerId == null || deviceId == null || deviceId.isBlank())
            return error("Missing workerId or deviceId");

        Worker worker = workers.findById(workerId).orElse(null);
        if (worker == null) return error("Worker not found");

        LocalDate today = LocalDate.now();

        // Validation 1: worker not already checked in today
        if (sessions.findTodaySession(workerId, house.getId(), today).isPresent())
            return error("Already checked in today");

        // Validation 2: device not used by another worker today at this house
        Optional<WorkSession> deviceSession = sessions.findTodaySessionByDevice(deviceId, house.getId(), today);
        if (deviceSession.isPresent() && !deviceSession.get().getWorker().getId().equals(workerId))
            return error("This device was already used by " + deviceSession.get().getWorker().getName() + " today");

        // Validation 3: GPS within 200m of house
        if (house.getLat() != null && lat != null) {
            double dist = haversineMeters(lat, lng, house.getLat().doubleValue(), house.getLng().doubleValue());
            if (dist > 200) return error("You are not on site (%.0f m away)".formatted(dist));
        }

        WorkSession s = new WorkSession();
        s.setWorker(worker);
        s.setHouse(house);
        s.setSessionDate(today);
        s.setCheckedInAt(OffsetDateTime.now(ZoneOffset.UTC));
        s.setDeviceId(deviceId);
        if (lat != null) { s.setCheckInLat(BigDecimal.valueOf(lat)); s.setCheckInLng(BigDecimal.valueOf(lng)); }
        sessions.save(s);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("sessionId",   s.getId());
        res.put("checkedInAt", s.getCheckedInAt().toString());
        return ResponseEntity.ok(res);
    }

    /** Check out a worker. */
    @PutMapping("/api/public/checkin/{token}/session/{sessionId}/checkout")
    @Transactional
    public ResponseEntity<?> checkOut(@PathVariable String token,
                                       @PathVariable Integer sessionId,
                                       @RequestBody Map<String, Object> body) {
        House house = houses.findByCheckinToken(token).orElse(null);
        if (house == null) return ResponseEntity.notFound().build();

        String deviceId = (String) body.get("deviceId");
        Double lat      = toDouble(body.get("lat"));
        Double lng      = toDouble(body.get("lng"));

        WorkSession s = sessions.findById(sessionId).orElse(null);
        if (s == null) return error("Session not found");

        // Validation 1: session belongs to this house
        if (!s.getHouse().getId().equals(house.getId())) return error("Session mismatch");

        // Validation 2: not already checked out
        if (s.getCheckedOutAt() != null) return error("Already checked out");

        // Validation 3: same device
        if (!s.getDeviceId().equals(deviceId))
            return error("Check out must be done from the same phone used to check in");

        // Validation 4: GPS
        if (house.getLat() != null && lat != null) {
            double dist = haversineMeters(lat, lng, house.getLat().doubleValue(), house.getLng().doubleValue());
            if (dist > 200) return error("You are not on site (%.0f m away)".formatted(dist));
        }

        s.setCheckedOutAt(OffsetDateTime.now(ZoneOffset.UTC));
        if (lat != null) { s.setCheckOutLat(BigDecimal.valueOf(lat)); s.setCheckOutLng(BigDecimal.valueOf(lng)); }
        sessions.save(s);

        long mins = ChronoUnit.MINUTES.between(s.getCheckedInAt(), s.getCheckedOutAt());
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("checkedOutAt",    s.getCheckedOutAt().toString());
        res.put("durationMinutes", mins);
        return ResponseEntity.ok(res);
    }

    // ── ADMIN: attendance views ──────────────────────────────────────────────

    /** All sessions for a crew on a given date. */
    @GetMapping("/api/attendance/crew/{crewId}")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getCrewAttendance(@PathVariable Integer crewId,
                                                @RequestParam String date) {
        LocalDate d = LocalDate.parse(date);
        List<WorkSession> list = sessions.findByCrewAndDate(crewId, d);
        return ResponseEntity.ok(list.stream().map(this::toDto).toList());
    }

    /** All sessions for a worker between two dates. */
    @GetMapping("/api/attendance/worker/{workerId}")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getWorkerAttendance(@PathVariable Integer workerId,
                                                  @RequestParam String from,
                                                  @RequestParam String to) {
        List<WorkSession> list = sessions.findByWorkerAndRange(workerId, LocalDate.parse(from), LocalDate.parse(to));
        return ResponseEntity.ok(list.stream().map(this::toDto).toList());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Map<String, Object> toDto(WorkSession s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",              s.getId());
        m.put("workerId",        s.getWorker().getId());
        m.put("workerName",      s.getWorker().getName());
        m.put("houseId",         s.getHouse().getId());
        m.put("houseName",       s.getHouse().getName());
        m.put("checkedInAt",     s.getCheckedInAt().toString());
        m.put("checkedOutAt",    s.getCheckedOutAt() != null ? s.getCheckedOutAt().toString() : null);
        if (s.getCheckedOutAt() != null) {
            long mins = ChronoUnit.MINUTES.between(s.getCheckedInAt(), s.getCheckedOutAt());
            m.put("durationMinutes", mins);
        } else {
            m.put("durationMinutes", null);
        }
        return m;
    }

    private ResponseEntity<Map<String,String>> error(String msg) {
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }

    private Double toDouble(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(v.toString()); } catch (Exception e) { return null; }
    }

    /** Haversine distance in metres between two lat/lng points. */
    private double haversineMeters(double lat1, double lng1, double lat2, double lng2) {
        final double R = 6_371_000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat/2) * Math.sin(dLat/2)
                 + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                 * Math.sin(dLng/2) * Math.sin(dLng/2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
    }
}
