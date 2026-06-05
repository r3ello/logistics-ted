package com.bellgado.logistics_ted.web;

import com.bellgado.logistics_ted.domain.House;
import com.bellgado.logistics_ted.domain.Worker;
import com.bellgado.logistics_ted.repository.HouseRepository;
import com.bellgado.logistics_ted.repository.WorkerRepository;
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
@RequestMapping("/api/workers")
public class WorkerController {

    private final WorkerRepository workers;
    private final HouseRepository  houses;

    public WorkerController(WorkerRepository workers, HouseRepository houses) {
        this.workers = workers;
        this.houses  = houses;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return workers.findAll().stream().map(this::toDto).toList();
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        String err = validateBody(body, true);
        if (err != null) return ResponseEntity.badRequest().body(Map.of("error", err));
        return ResponseEntity.ok(toDto(workers.save(applyBody(new Worker(), body))));
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable Integer id, @RequestBody Map<String, Object> body) {
        String err = validateBody(body, false);
        if (err != null) return ResponseEntity.badRequest().body(Map.of("error", err));
        return workers.findById(id)
            .map(w -> ResponseEntity.ok(toDto(workers.save(applyBody(w, body)))))
            .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Integer id) {
        if (!workers.existsById(id)) return ResponseEntity.notFound().build();
        workers.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private String validateBody(Map<String, Object> body, boolean requireName) {
        if (requireName) {
            Object name = body.get("name");
            if (name == null || name.toString().isBlank()) return "Worker name is required.";
        }
        if (body.get("lat") != null && body.get("lng") != null) {
            try {
                double lat = Double.parseDouble(body.get("lat").toString());
                double lng = Double.parseDouble(body.get("lng").toString());
                if (lat < -90 || lat > 90)   return "Latitude must be between -90 and 90.";
                if (lng < -180 || lng > 180) return "Longitude must be between -180 and 180.";
            } catch (NumberFormatException e) {
                return "Invalid coordinates.";
            }
        }
        return null;
    }

    private Worker applyBody(Worker w, Map<String, Object> body) {
        if (body.get("name")     != null) w.setName((String) body.get("name"));
        if (body.containsKey("location")) w.setLocation(body.getOrDefault("location", "").toString());
        if (body.get("lat")      != null) w.setLat(new java.math.BigDecimal(body.get("lat").toString()));
        if (body.get("lng")      != null) w.setLng(new java.math.BigDecimal(body.get("lng").toString()));
        if (body.containsKey("crew"))     w.setCrew(body.get("crew") != null ? body.get("crew").toString() : null);
        if (body.containsKey("houseId")) {
            if (body.get("houseId") == null) {
                w.setHouse(null);
            } else {
                Integer hid = Integer.parseInt(body.get("houseId").toString());
                House h = houses.findById(hid).orElse(null);
                w.setHouse(h);
            }
        }
        return w;
    }

    private Map<String, Object> toDto(Worker w) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",       w.getId());
        m.put("name",     w.getName());
        m.put("location", w.getLocation());
        m.put("lat",      w.getLat());
        m.put("lng",      w.getLng());
        m.put("crew",     w.getCrew());
        if (w.getHouse() != null) {
            m.put("houseId",   w.getHouse().getId());
            m.put("houseName", w.getHouse().getName());
        } else {
            m.put("houseId",   null);
            m.put("houseName", null);
        }
        return m;
    }
}
