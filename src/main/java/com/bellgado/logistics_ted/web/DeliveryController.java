package com.bellgado.logistics_ted.web;

import com.bellgado.logistics_ted.domain.Delivery;
import com.bellgado.logistics_ted.domain.DeliveryItem;
import com.bellgado.logistics_ted.domain.HouseOrder;
import com.bellgado.logistics_ted.domain.Material;
import com.bellgado.logistics_ted.domain.Worker;
import com.bellgado.logistics_ted.repository.DeliveryRepository;
import com.bellgado.logistics_ted.repository.HouseOrderRepository;
import com.bellgado.logistics_ted.repository.MaterialRepository;
import com.bellgado.logistics_ted.repository.WorkerRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class DeliveryController {

    private final DeliveryRepository deliveries;
    private final HouseOrderRepository orders;
    private final MaterialRepository materials;
    private final WorkerRepository workers;

    public DeliveryController(DeliveryRepository deliveries, HouseOrderRepository orders, MaterialRepository materials, WorkerRepository workers) {
        this.deliveries = deliveries;
        this.orders     = orders;
        this.materials  = materials;
        this.workers    = workers;
    }

    @GetMapping("/deliveries")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listAll() {
        return deliveries.findAllWithDetails().stream().map(this::toDto).toList();
    }

    @GetMapping("/house-orders/{orderId}/deliveries")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listForOrder(@PathVariable Integer orderId) {
        return deliveries.findByOrderId(orderId).stream().map(this::toDto).toList();
    }

    @PostMapping("/house-orders/{orderId}/deliveries")
    @Transactional
    public ResponseEntity<?> create(@PathVariable Integer orderId, @RequestBody Map<String, Object> body) {
        HouseOrder order = orders.findById(orderId).orElse(null);
        if (order == null) return ResponseEntity.notFound().build();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> itemList = (List<Map<String, Object>>) body.get("items");
        if (itemList == null || itemList.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "At least one material is required."));

        Delivery d = new Delivery();
        d.setOrder(order);
        d.setNotes(body.get("notes") != null ? body.get("notes").toString() : null);
        if (body.get("deliveredAt") != null)
            d.setDeliveredAt(LocalDateTime.parse(body.get("deliveredAt").toString()));
        if (body.get("responsibleId") != null) {
            Worker w = workers.findById(Integer.parseInt(body.get("responsibleId").toString()))
                .orElseThrow(() -> new IllegalArgumentException("Worker not found"));
            d.setResponsible(w);
        }

        for (Map<String, Object> item : itemList) {
            Material m = materials.findById(Integer.parseInt(item.get("materialId").toString()))
                .orElseThrow(() -> new IllegalArgumentException("Material not found"));
            DeliveryItem di = new DeliveryItem();
            di.setDelivery(d);
            di.setMaterial(m);
            di.setQtyDelivered(new BigDecimal(item.get("qtyDelivered").toString()));
            d.getItems().add(di);
        }
        deliveries.save(d);

        // Auto-flip order to DELIVERED if all materials fully covered
        autoUpdateOrderStatus(order);

        return ResponseEntity.ok(toDto(d));
    }

    @DeleteMapping("/deliveries/{id}")
    @Transactional
    public ResponseEntity<?> delete(@PathVariable Integer id) {
        return deliveries.findById(id).map(d -> {
            HouseOrder order = d.getOrder();
            deliveries.deleteById(id);
            deliveries.flush();
            autoUpdateOrderStatus(order);
            return ResponseEntity.noContent().build();
        }).orElse(ResponseEntity.notFound().build());
    }

    private void autoUpdateOrderStatus(HouseOrder staleOrder) {
        // Reload order fresh to avoid stale items collection
        HouseOrder order = orders.findAllWithDetails().stream()
            .filter(o -> o.getId().equals(staleOrder.getId())).findFirst().orElse(staleOrder);
        List<Delivery> dels = deliveries.findByOrderId(order.getId());

        // Sum delivered per material
        Map<Integer, BigDecimal> delivered = new java.util.HashMap<>();
        for (Delivery del : dels)
            for (DeliveryItem di : del.getItems())
                delivered.merge(di.getMaterial().getId(), di.getQtyDelivered(), BigDecimal::add);

        // Check if every ordered item is fully covered
        boolean fullyDelivered = !order.getItems().isEmpty() && order.getItems().stream().allMatch(item ->
            delivered.getOrDefault(item.getMaterial().getId(), BigDecimal.ZERO)
                .compareTo(item.getQuantity()) >= 0);

        String newStatus = fullyDelivered ? "DELIVERED" :
            (delivered.isEmpty() ? "DRAFT" : "CONFIRMED");

        if (!order.getStatus().equals("CANCELLED")) {
            order.setStatus(newStatus);
            order.setUpdatedAt(LocalDateTime.now());
            orders.save(order);
        }
    }

    private Map<String, Object> toDto(Delivery d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",          d.getId());
        m.put("orderId",     d.getOrder().getId());
        m.put("houseName",   d.getOrder().getHouse().getName());
        m.put("deliveredAt",      d.getDeliveredAt().toString());
        m.put("notes",            d.getNotes());
        m.put("responsibleId",   d.getResponsible() != null ? d.getResponsible().getId() : null);
        m.put("responsibleName", d.getResponsible() != null ? d.getResponsible().getName() : null);
        m.put("items", d.getItems().stream().map(i -> {
            Map<String, Object> im = new LinkedHashMap<>();
            im.put("materialId",   i.getMaterial().getId());
            im.put("materialName", i.getMaterial().getName());
            im.put("unit",         i.getMaterial().getUnit());
            im.put("qtyDelivered", i.getQtyDelivered());
            return im;
        }).toList());
        return m;
    }
}
