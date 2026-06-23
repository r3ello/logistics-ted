package com.bellgado.logistics_ted.web;

import com.bellgado.logistics_ted.domain.Supplier;
import com.bellgado.logistics_ted.service.SupplierService;
import com.bellgado.logistics_ted.web.dto.SupplierDto;
import com.bellgado.logistics_ted.web.dto.SupplierUpsertRequest;
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
 * Admin CRUD for external suppliers — tier-3 routing nodes. Mirrors {@link DepotController};
 * the inventory editor additionally accepts an optional per-material unit price.
 */
@RestController
@RequestMapping("/api")
public class SupplierController {

    private final SupplierService service;

    public SupplierController(SupplierService service) {
        this.service = service;
    }

    @GetMapping("/suppliers")
    public List<SupplierDto> list() {
        return service.listAll();
    }

    @PostMapping("/suppliers")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> create(@RequestBody SupplierUpsertRequest req) {
        try {
            Supplier created = service.create(req);
            return ResponseEntity.ok(Map.of("ok", true, "id", created.getId()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PutMapping("/suppliers/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> update(@PathVariable Integer id, @RequestBody SupplierUpsertRequest req) {
        try {
            service.update(id, req);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        } catch (EntityNotFoundException ex) {
            return ResponseEntity.status(404).body(Map.of("error", ex.getMessage()));
        }
    }

    @DeleteMapping("/suppliers/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> delete(@PathVariable Integer id) {
        try {
            service.delete(id);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (EntityNotFoundException ex) {
            return ResponseEntity.status(404).body(Map.of("error", ex.getMessage()));
        }
    }

    @PutMapping("/suppliers/{id}/inventory")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> upsertInventory(@PathVariable("id") Integer supplierId,
                                             @RequestBody Map<String, Object> body) {
        try {
            service.upsertInventory(supplierId, body);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (EntityNotFoundException ex) {
            return ResponseEntity.status(404).body(Map.of("error", ex.getMessage()));
        }
    }
}
