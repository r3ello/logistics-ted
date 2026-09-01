package com.bellgado.logistics_ted.web;

import com.bellgado.logistics_ted.domain.Material;
import com.bellgado.logistics_ted.repository.MaterialRepository;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/materials")
public class MaterialController {

    private final MaterialRepository materials;

    public MaterialController(MaterialRepository materials) {
        this.materials = materials;
    }

    @GetMapping
    public List<Material> list() {
        return materials.findAllByOrderByIdAsc();
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        String err = validate(body);
        if (err != null) return ResponseEntity.badRequest().body(Map.of("error", err));
        Material m = new Material();
        apply(m, body);
        return ResponseEntity.ok(materials.save(m));
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable Integer id, @RequestBody Map<String, Object> body) {
        String err = validate(body);
        if (err != null) return ResponseEntity.badRequest().body(Map.of("error", err));
        return materials.findById(id)
            .map(m -> { apply(m, body); return ResponseEntity.ok(materials.save(m)); })
            .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> delete(@PathVariable Integer id) {
        if (!materials.existsById(id)) return ResponseEntity.notFound().build();
        materials.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private String validate(Map<String, Object> body) {
        if (body.get("name") == null || body.get("name").toString().isBlank()) return "Name is required.";
        if (body.get("unit") == null || body.get("unit").toString().isBlank()) return "Unit is required.";
        return null;
    }

    private void apply(Material m, Map<String, Object> body) {
        m.setName(body.get("name").toString().trim());
        m.setUnit(body.get("unit").toString().trim());
    }
}
