package com.bellgado.logistics_ted.web;

import com.bellgado.logistics_ted.domain.Worker;
import com.bellgado.logistics_ted.repository.WorkerRepository;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
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

    public WorkerController(WorkerRepository workers) {
        this.workers = workers;
    }

    @GetMapping
    public List<Worker> list() {
        return workers.findAll();
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        Worker w = new Worker();
        w.setName((String) body.get("name"));
        w.setLocation(body.getOrDefault("location", "").toString());
        if (body.get("lat") != null) w.setLat(new java.math.BigDecimal(body.get("lat").toString()));
        if (body.get("lng") != null) w.setLng(new java.math.BigDecimal(body.get("lng").toString()));
        if (body.containsKey("crew")) w.setCrew(body.get("crew") != null ? body.get("crew").toString() : null);
        return ResponseEntity.ok(workers.save(w));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Integer id, @RequestBody Map<String, Object> body) {
        return workers.findById(id).map(w -> {
            if (body.get("name") != null) w.setName((String) body.get("name"));
            if (body.containsKey("location")) w.setLocation(body.get("location").toString());
            if (body.get("lat") != null) w.setLat(new java.math.BigDecimal(body.get("lat").toString()));
            if (body.get("lng") != null) w.setLng(new java.math.BigDecimal(body.get("lng").toString()));
            if (body.containsKey("crew")) w.setCrew(body.get("crew") != null ? body.get("crew").toString() : null);
            return ResponseEntity.ok(workers.save(w));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Integer id) {
        if (!workers.existsById(id)) return ResponseEntity.notFound().build();
        workers.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
