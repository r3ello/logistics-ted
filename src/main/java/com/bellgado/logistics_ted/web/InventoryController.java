package com.bellgado.logistics_ted.web;

import com.bellgado.logistics_ted.service.InventoryService;
import jakarta.persistence.EntityNotFoundException;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class InventoryController {

    private final InventoryService service;

    public InventoryController(InventoryService service) {
        this.service = service;
    }

    @PutMapping("/houses/{id}/inventory")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> upsert(@PathVariable("id") Integer houseId, @RequestBody Map<String, Object> quantities) {
        try {
            service.upsertForHouse(houseId, quantities);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (EntityNotFoundException ex) {
            return ResponseEntity.status(404).body(Map.of("error", ex.getMessage()));
        }
    }
}
