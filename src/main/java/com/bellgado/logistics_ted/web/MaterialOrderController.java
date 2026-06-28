package com.bellgado.logistics_ted.web;

import com.bellgado.logistics_ted.domain.House;
import com.bellgado.logistics_ted.domain.MaterialOrder;
import com.bellgado.logistics_ted.domain.MaterialOrderItem;
import com.bellgado.logistics_ted.domain.Material;
import com.bellgado.logistics_ted.domain.Worker;
import com.bellgado.logistics_ted.repository.MaterialOrderRepository;
import com.bellgado.logistics_ted.repository.HouseRepository;
import com.bellgado.logistics_ted.repository.MaterialRepository;
import com.bellgado.logistics_ted.repository.WorkerRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/material-orders")
public class MaterialOrderController {

    private final MaterialOrderRepository orders;
    private final HouseRepository houses;
    private final MaterialRepository materials;
    private final WorkerRepository workers;
    private final EntityManager em;

    public MaterialOrderController(MaterialOrderRepository orders, HouseRepository houses, MaterialRepository materials, WorkerRepository workers, EntityManager em) {
        this.orders    = orders;
        this.houses    = houses;
        this.materials = materials;
        this.workers   = workers;
        this.em        = em;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return orders.findAllWithDetails().stream().map(this::toDto).toList();
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        String err = validate(body, true);
        if (err != null) return ResponseEntity.badRequest().body(Map.of("error", err));
        MaterialOrder o = applyBody(new MaterialOrder(), body);
        return ResponseEntity.ok(toDto(orders.save(o)));
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable Integer id, @RequestBody Map<String, Object> body) {
        String err = validate(body, false);
        if (err != null) return ResponseEntity.badRequest().body(Map.of("error", err));
        return orders.findById(id)
            .map(o -> ResponseEntity.ok(toDto(orders.save(applyBody(o, body)))))
            .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Integer id) {
        if (!orders.existsById(id)) return ResponseEntity.notFound().build();
        orders.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private String validate(Map<String, Object> body, boolean requireHouse) {
        if (requireHouse && body.get("houseId") == null) return "House is required.";
        Object items = body.get("items");
        if (requireHouse && (items == null || ((List<?>) items).isEmpty())) return "At least one material is required.";
        return null;
    }

    @SuppressWarnings("unchecked")
    private MaterialOrder applyBody(MaterialOrder o, Map<String, Object> body) {
        if (body.get("houseId") != null) {
            House h = houses.findById(Integer.parseInt(body.get("houseId").toString()))
                .orElseThrow(() -> new IllegalArgumentException("House not found"));
            o.setHouse(h);
        }
        if (body.containsKey("status") && body.get("status") != null) {
            o.setStatus(body.get("status").toString());
        }
        if (body.containsKey("notes")) {
            o.setNotes(body.get("notes") != null ? body.get("notes").toString() : null);
        }
        if (body.containsKey("createdById")) {
            if (body.get("createdById") != null) {
                Worker w = workers.findById(Integer.parseInt(body.get("createdById").toString()))
                    .orElseThrow(() -> new IllegalArgumentException("Worker not found"));
                o.setCreatedBy(w);
            } else {
                o.setCreatedBy(null);
            }
        }
        o.setUpdatedAt(LocalDateTime.now());

        if (body.containsKey("items")) {
            o.getItems().clear();
            em.flush();
            List<Map<String, Object>> itemList = (List<Map<String, Object>>) body.get("items");
            for (Map<String, Object> item : itemList) {
                Material m = materials.findById(Integer.parseInt(item.get("materialId").toString()))
                    .orElseThrow(() -> new IllegalArgumentException("Material not found"));
                MaterialOrderItem i = new MaterialOrderItem();
                i.setOrder(o);
                i.setMaterial(m);
                i.setQuantity(new BigDecimal(item.get("quantity").toString()));
                o.getItems().add(i);
            }
        }
        return o;
    }

    private Map<String, Object> toDto(MaterialOrder o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",        o.getId());
        m.put("houseId",   o.getHouse().getId());
        m.put("houseName", o.getHouse().getName());
        m.put("status",        o.getStatus());
        m.put("notes",         o.getNotes());
        m.put("createdById",   o.getCreatedBy() != null ? o.getCreatedBy().getId() : null);
        m.put("createdByName", o.getCreatedBy() != null ? o.getCreatedBy().getName() : null);
        m.put("createdAt", o.getCreatedAt().toString());
        m.put("updatedAt", o.getUpdatedAt().toString());
        m.put("items", o.getItems().stream().map(i -> {
            Map<String, Object> im = new LinkedHashMap<>();
            im.put("materialId",   i.getMaterial().getId());
            im.put("materialName", i.getMaterial().getName());
            im.put("unit",         i.getMaterial().getUnit());
            im.put("quantity",     i.getQuantity());
            return im;
        }).toList());
        return m;
    }
}
