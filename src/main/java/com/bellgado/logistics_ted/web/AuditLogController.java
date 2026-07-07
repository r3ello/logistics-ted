package com.bellgado.logistics_ted.web;

import com.bellgado.logistics_ted.service.AuditLogService;
import com.bellgado.logistics_ted.web.dto.AuditLogDto;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only bitácora browsing. Deliberately GET-only — audit rows are append-only frozen
 * records; no endpoint may ever mutate or delete them.
 */
@RestController
@RequestMapping("/api/audit")
public class AuditLogController {

    private final AuditLogService audit;

    public AuditLogController(AuditLogService audit) {
        this.audit = audit;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> list(@RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "20") int size,
                                  @RequestParam(required = false) String username,
                                  @RequestParam(required = false) String action,
                                  @RequestParam(required = false) String entity,
                                  @RequestParam(required = false) String from,
                                  @RequestParam(required = false) String to) {
        Instant fromTs;
        Instant toTs;
        try {
            fromTs = parseDay(from, false);
            toTs = parseDay(to, true);
        } catch (DateTimeParseException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid date — expected yyyy-MM-dd"));
        }
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size)));
        Page<AuditLogDto> result = audit.search(username, action, entity, fromTs, toTs, pageable);
        return ResponseEntity.ok(Map.of(
            "items", result.getContent(),
            "page", result.getNumber(),
            "size", result.getSize(),
            "total", result.getTotalElements(),
            "totalPages", result.getTotalPages()
        ));
    }

    /** yyyy-MM-dd → UTC day bound; the `to` bound is exclusive (start of the next day). */
    private static Instant parseDay(String day, boolean exclusiveEnd) {
        if (day == null || day.isBlank()) return null;
        LocalDate d = LocalDate.parse(day.trim());
        return (exclusiveEnd ? d.plusDays(1) : d).atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
