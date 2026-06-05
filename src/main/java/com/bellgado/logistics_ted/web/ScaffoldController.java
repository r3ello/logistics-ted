package com.bellgado.logistics_ted.web;

import com.bellgado.logistics_ted.domain.House;
import com.bellgado.logistics_ted.domain.Scaffold;
import com.bellgado.logistics_ted.domain.ScaffoldStatus;
import com.bellgado.logistics_ted.repository.HouseRepository;
import com.bellgado.logistics_ted.repository.ScaffoldRepository;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/scaffolds")
public class ScaffoldController {

    private final ScaffoldRepository scaffolds;
    private final HouseRepository    houses;

    public ScaffoldController(ScaffoldRepository scaffolds, HouseRepository houses) {
        this.scaffolds = scaffolds;
        this.houses    = houses;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return scaffolds.findAll().stream().map(this::toDto).toList();
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        String err = validateScaffoldBody(body, -1);
        if (err != null) return ResponseEntity.badRequest().body(Map.of("error", err));
        return ResponseEntity.ok(toDto(scaffolds.save(applyBody(new Scaffold(), body))));
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable Integer id, @RequestBody Map<String, Object> body) {
        String err = validateScaffoldBody(body, id);
        if (err != null) return ResponseEntity.badRequest().body(Map.of("error", err));
        return scaffolds.findById(id)
            .map(s -> ResponseEntity.ok(toDto(scaffolds.save(applyBody(s, body)))))
            .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> delete(@PathVariable Integer id) {
        if (!scaffolds.existsById(id)) return ResponseEntity.notFound().build();
        scaffolds.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private String validateScaffoldBody(Map<String, Object> body, Integer excludeId) {
        if (body.get("houseId") != null) {
            Integer hid = Integer.parseInt(body.get("houseId").toString());
            if (scaffolds.existsByHouseIdAndIdNot(hid, excludeId))
                return "This house already has a scaffold assigned.";
        }
        LocalDate start = parseDate((String) body.getOrDefault("startDate", null));
        LocalDate end   = parseDate((String) body.getOrDefault("endDate",   null));
        if (start != null && end != null && end.isBefore(start))
            return "End date must be on or after the start date.";
        return null;
    }

    private Scaffold applyBody(Scaffold s, Map<String, Object> body) {
        if (body.get("status") != null)
            s.setStatus(ScaffoldStatus.valueOf(body.get("status").toString()));
        if (body.containsKey("startDate"))
            s.setStartDate(parseDate((String) body.get("startDate")));
        if (body.containsKey("endDate"))
            s.setEndDate(parseDate((String) body.get("endDate")));
        if (body.containsKey("houseId")) {
            if (body.get("houseId") == null) {
                s.setHouse(null);
            } else {
                Integer hid = Integer.parseInt(body.get("houseId").toString());
                s.setHouse(houses.findById(hid).orElse(null));
            }
        }
        return s;
    }

    private Map<String, Object> toDto(Scaffold s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",        s.getId());
        m.put("status",    s.getStatus());
        m.put("startDate", s.getStartDate() != null ? s.getStartDate().toString() : null);
        m.put("endDate",   s.getEndDate()   != null ? s.getEndDate().toString()   : null);
        m.put("houseId",   s.getHouse() != null ? s.getHouse().getId()   : null);
        m.put("houseName", s.getHouse() != null ? s.getHouse().getName() : null);
        return m;
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try { return LocalDate.parse(s); } catch (Exception e) { return null; }
    }
}
