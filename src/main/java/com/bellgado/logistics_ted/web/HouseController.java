package com.bellgado.logistics_ted.web;

import com.bellgado.logistics_ted.service.HouseService;
import com.bellgado.logistics_ted.service.HouseTemplateFolderService;
import com.bellgado.logistics_ted.web.dto.HouseDto;
import com.bellgado.logistics_ted.web.dto.HouseResponse;
import com.bellgado.logistics_ted.web.dto.HouseUpsertRequest;
import com.bellgado.logistics_ted.web.dto.MaterialTotalDto;
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

@RestController
@RequestMapping("/api")
public class HouseController {

    private final HouseService service;
    private final HouseTemplateFolderService houseTemplate;

    public HouseController(HouseService service, HouseTemplateFolderService houseTemplate) {
        this.service = service;
        this.houseTemplate = houseTemplate;
    }

    @GetMapping("/houses")
    public List<HouseDto> list() {
        return service.listAll();
    }

    @GetMapping("/totals")
    public List<MaterialTotalDto> totals() {
        return service.totals();
    }

    @PostMapping("/houses")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> create(@RequestBody HouseUpsertRequest req) {
        try {
            HouseResponse created = service.create(req);
            return ResponseEntity.ok(created);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PutMapping("/houses/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> update(@PathVariable Integer id, @RequestBody HouseUpsertRequest req) {
        try {
            service.update(id, req);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @org.springframework.web.bind.annotation.PatchMapping("/houses/{id}/scaffold")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> updateScaffold(@PathVariable Integer id,
                                            @RequestBody Map<String, Object> body) {
        try {
            service.updateScaffold(id, body);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping("/admin/houses/seed-template-folders")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> seedTemplateFolders() {
        int count = houseTemplate.seedAllHouses();
        return ResponseEntity.ok(Map.of("seeded", count));
    }

    @DeleteMapping("/houses/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> delete(@PathVariable Integer id) {
        service.delete(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }
}
