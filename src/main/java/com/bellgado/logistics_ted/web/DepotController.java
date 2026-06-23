package com.bellgado.logistics_ted.web;

import com.bellgado.logistics_ted.domain.Depot;
import com.bellgado.logistics_ted.service.DepotService;
import com.bellgado.logistics_ted.web.dto.DepotDto;
import com.bellgado.logistics_ted.web.dto.DepotUpsertRequest;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin CRUD for company warehouses (depots) — tier-2 routing nodes. Path is
 * {@code /api/warehouses} (UI label "Warehouse"); the entity is {@code depot} internally.
 * Mirrors {@link HouseController} / {@link InventoryController}.
 */
@RestController
@RequestMapping("/api")
public class DepotController {

    private final DepotService service;

    public DepotController(DepotService service) {
        this.service = service;
    }

    @GetMapping("/warehouses")
    public List<DepotDto> list() {
        return service.listAll();
    }

    @PostMapping("/warehouses")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> create(@RequestBody DepotUpsertRequest req) {
        try {
            Depot created = service.create(req);
            return ResponseEntity.ok(Map.of("ok", true, "id", created.getId()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PutMapping("/warehouses/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> update(@PathVariable Integer id, @RequestBody DepotUpsertRequest req) {
        try {
            service.update(id, req);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        } catch (EntityNotFoundException ex) {
            return ResponseEntity.status(404).body(Map.of("error", ex.getMessage()));
        }
    }

    @DeleteMapping("/warehouses/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> delete(@PathVariable Integer id) {
        try {
            service.delete(id);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (EntityNotFoundException ex) {
            return ResponseEntity.status(404).body(Map.of("error", ex.getMessage()));
        }
    }

    @PutMapping("/warehouses/{id}/inventory")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> upsertInventory(@PathVariable("id") Integer depotId,
                                             @RequestBody Map<String, Object> quantities) {
        try {
            service.upsertInventory(depotId, quantities);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (EntityNotFoundException ex) {
            return ResponseEntity.status(404).body(Map.of("error", ex.getMessage()));
        }
    }
}
