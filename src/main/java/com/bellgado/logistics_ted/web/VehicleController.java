package com.bellgado.logistics_ted.web;

import com.bellgado.logistics_ted.gps.GpsHunterProperties;
import com.bellgado.logistics_ted.gps.GpsJob;
import com.bellgado.logistics_ted.gps.GpsSyncState;
import com.bellgado.logistics_ted.gps.VehicleTrackingService;
import com.bellgado.logistics_ted.web.dto.VehicleDto;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Fleet (GPS.bg) API. Admin-only, here and in SecurityConfig: vehicle positions are where people are.
 *
 * <p>Nothing here calls GPS.bg. The provider allows one call per 181 s per account, so every read is
 * served from Postgres and "sync" only queues work for {@code GpsPoller}. Days ({@code date=yyyy-MM-dd})
 * are calendar days in the provider's zone (Europe/Sofia); the default is today.
 */
@RestController
@RequestMapping("/api/vehicles")
@PreAuthorize("hasRole('ADMIN')")
public class VehicleController {

    private final VehicleTrackingService tracking;
    private final GpsSyncState sync;
    private final GpsHunterProperties props;

    public VehicleController(VehicleTrackingService tracking, GpsSyncState sync, GpsHunterProperties props) {
        this.tracking = tracking;
        this.sync = sync;
        this.props = props;
    }

    @GetMapping
    public List<VehicleDto> list() {
        return tracking.list();
    }

    /** Is polling on here, when did GPS.bg last answer, what went wrong last. */
    @GetMapping("/sync-status")
    public GpsSyncState.Snapshot syncStatus() {
        return sync.snapshot();
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable Integer id) {
        return okOrNotFound(tracking.get(id));
    }

    /** Our own fields: alias, notes, active, driverWorkerId. The body replaces all four. */
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Integer id, @RequestBody Map<String, Object> body) {
        String alias = str(body.get("alias"));
        String notes = str(body.get("notes"));
        if (alias != null && alias.trim().length() > 255) return bad("Alias is too long (255 characters max).");
        if (notes != null && notes.length() > 2000) return bad("Notes are too long (2000 characters max).");
        boolean active = body.get("active") == null || Boolean.parseBoolean(body.get("active").toString());
        Integer driverWorkerId;
        try {
            driverWorkerId = intOrNull(body.get("driverWorkerId"));
        } catch (NumberFormatException e) {
            return bad("driverWorkerId must be a number.");
        }
        try {
            return okOrNotFound(tracking.update(id, alias, notes, active, driverWorkerId));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    /** The breadcrumb track: one point per poll that saw a new fix. */
    @GetMapping("/{id}/positions")
    public ResponseEntity<?> positions(@PathVariable Integer id, @RequestParam(required = false) String date) {
        LocalDate day;
        try {
            day = day(date);
        } catch (DateTimeParseException e) {
            return badDate();
        }
        return okOrNotFound(tracking.positions(id, day, props.zone()));
    }

    @GetMapping("/{id}/trips")
    public ResponseEntity<?> trips(@PathVariable Integer id, @RequestParam(required = false) String date) {
        LocalDate day;
        try {
            day = day(date);
        } catch (DateTimeParseException e) {
            return badDate();
        }
        return okOrNotFound(tracking.trips(id, day, props.zone()));
    }

    /**
     * Queues a trip-history fetch for one vehicle and day. 202 with the queue position; the poller
     * gets to it at its next trip slot, so the answer is minutes, not immediate.
     */
    @PostMapping("/{id}/trips/sync")
    public ResponseEntity<?> requestTripSync(@PathVariable Integer id, @RequestParam(required = false) String date) {
        if (!props.usable()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "GPS polling is disabled in this environment."));
        }
        LocalDate day;
        try {
            day = day(date);
        } catch (DateTimeParseException e) {
            return badDate();
        }
        LocalDate today = LocalDate.now(props.zone());
        if (day.isAfter(today)) return bad("Date is in the future.");
        Optional<String> code = tracking.gpsCodeOf(id);
        if (code.isEmpty()) return ResponseEntity.notFound().build();

        int position = sync.enqueue(new GpsJob.Routes(code.get(), day, day.isBefore(today)));
        if (position < 0) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Map.of("error", "The GPS sync queue is full. Try again later."));
        }
        // Upper bound: by day only one slot in routeSlotEvery fetches trips.
        long etaSeconds = (long) position * props.effectiveRouteSlotEvery() * props.effectiveSlotSeconds();
        return ResponseEntity.accepted().body(Map.of("queued", true, "position", position, "etaSeconds", etaSeconds));
    }

    private LocalDate day(String date) {
        return date == null || date.isBlank() ? LocalDate.now(props.zone()) : LocalDate.parse(date.trim());
    }

    private static <T> ResponseEntity<?> okOrNotFound(Optional<T> value) {
        return value.<ResponseEntity<?>>map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static ResponseEntity<?> bad(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }

    private static ResponseEntity<?> badDate() {
        return bad("Invalid date — expected yyyy-MM-dd.");
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static Integer intOrNull(Object o) {
        if (o == null || o.toString().isBlank()) return null;
        if (o instanceof Number n) return n.intValue();
        return Integer.valueOf(o.toString().trim());
    }
}
