package com.bellgado.logistics_ted.web;

import com.bellgado.logistics_ted.domain.ElectricBox;
import com.bellgado.logistics_ted.domain.ElectricCircuit;
import com.bellgado.logistics_ted.repository.ElectricBoxRepository;
import com.bellgado.logistics_ted.repository.ElectricCircuitRepository;
import com.bellgado.logistics_ted.repository.HouseRepository;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
public class ElectricBoxController {

    private final ElectricBoxRepository boxes;
    private final ElectricCircuitRepository circuits;
    private final HouseRepository houses;
    private static final SecureRandom RNG = new SecureRandom();

    public ElectricBoxController(ElectricBoxRepository boxes, ElectricCircuitRepository circuits, HouseRepository houses) {
        this.boxes = boxes;
        this.circuits = circuits;
        this.houses = houses;
    }

    // ── Manager endpoints (authenticated) ──────────────────────────────────

    @GetMapping("/api/electric-boxes")
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN')")
    public List<Map<String, Object>> list() {
        return boxes.findAll().stream().map(b -> toDto(b, false)).toList();
    }

    @GetMapping("/api/electric-boxes/house/{houseId}")
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> getByHouse(@PathVariable Integer houseId) {
        return boxes.findByHouseId(houseId)
            .map(b -> ResponseEntity.ok(toDto(b, true)))
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/api/electric-boxes")
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        Integer houseId = intVal(body.get("houseId"));
        if (houseId == null) return ResponseEntity.badRequest().body(Map.of("error", "houseId required"));
        if (boxes.existsByHouseId(houseId))
            return ResponseEntity.badRequest().body(Map.of("error", "This house already has an electric box."));

        ElectricBox box = new ElectricBox();
        box.setHouse(houses.findById(houseId).orElse(null));
        if (box.getHouse() == null) return ResponseEntity.badRequest().body(Map.of("error", "House not found"));
        box.setToken(generateToken());
        applyMeta(box, body);
        applyCircuits(box, body);
        return ResponseEntity.ok(toDto(boxes.save(box), true));
    }

    @PutMapping("/api/electric-boxes/{id}")
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> update(@PathVariable Integer id, @RequestBody Map<String, Object> body) {
        return boxes.findById(id).map(box -> {
            applyMeta(box, body);
            if (body.containsKey("circuits")) {
                circuits.deleteByBoxId(box.getId());
                circuits.flush();
                box.getCircuits().clear();
            }
            applyCircuits(box, body);
            return ResponseEntity.ok(toDto(boxes.save(box), true));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/api/electric-boxes/{id}")
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> delete(@PathVariable Integer id) {
        if (!boxes.existsById(id)) return ResponseEntity.notFound().build();
        boxes.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // ── Public client endpoint (token-based, no auth) ──────────────────────

    @GetMapping("/api/public/electric-box/{token}")
    @Transactional(readOnly = true)
    public ResponseEntity<?> clientView(@PathVariable String token) {
        return boxes.findByToken(token)
            .map(b -> ResponseEntity.ok(toDto(b, true)))
            .orElse(ResponseEntity.notFound().build());
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private void applyMeta(ElectricBox box, Map<String, Object> body) {
        if (body.get("mainAmps") != null) box.setMainAmps(intVal(body.get("mainAmps")));
        if (body.containsKey("label")) box.setLabel((String) body.get("label"));
    }

    @SuppressWarnings("unchecked")
    private void applyCircuits(ElectricBox box, Map<String, Object> body) {
        if (!body.containsKey("circuits")) return;
        List<Map<String, Object>> list = (List<Map<String, Object>>) body.get("circuits");
        box.getCircuits().clear();
        if (list == null) return;
        for (Map<String, Object> c : list) {
            ElectricCircuit circuit = new ElectricCircuit();
            circuit.setBox(box);
            circuit.setSlotIndex(intVal(c.get("slotIndex")));
            circuit.setSide(strVal(c.get("side"), "LEFT"));
            circuit.setLabel((String) c.get("label"));
            circuit.setAmps(intVal(c.get("amps")));
            circuit.setType(strVal(c.get("type"), "SINGLE"));
            circuit.setStatus(strVal(c.get("status"), "ON"));
            box.getCircuits().add(circuit);
        }
    }

    private Map<String, Object> toDto(ElectricBox b, boolean includeCircuits) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",        b.getId());
        m.put("houseId",   b.getHouse() != null ? b.getHouse().getId()   : null);
        m.put("houseName", b.getHouse() != null ? b.getHouse().getName() : null);
        m.put("mainAmps",  b.getMainAmps());
        m.put("label",     b.getLabel());
        m.put("token",     b.getToken());
        m.put("createdAt", b.getCreatedAt() != null ? b.getCreatedAt().toString() : null);
        if (includeCircuits) {
            m.put("circuits", b.getCircuits().stream().map(c -> {
                Map<String, Object> cm = new LinkedHashMap<>();
                cm.put("id",        c.getId());
                cm.put("slotIndex", c.getSlotIndex());
                cm.put("side",      c.getSide());
                cm.put("label",     c.getLabel());
                cm.put("amps",      c.getAmps());
                cm.put("type",      c.getType());
                cm.put("status",    c.getStatus());
                return cm;
            }).toList());
        }
        return m;
    }

    private static String generateToken() {
        byte[] bytes = new byte[32];
        RNG.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static Integer intVal(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return null; }
    }

    private static String strVal(Object v, String fallback) {
        return v != null ? v.toString() : fallback;
    }
}
