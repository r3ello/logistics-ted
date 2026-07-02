package com.bellgado.logistics_ted.web;

import com.bellgado.logistics_ted.domain.HouseStage;
import com.bellgado.logistics_ted.domain.MaterialOrder;
import com.bellgado.logistics_ted.service.CrewLeaderOrderService;
import com.bellgado.logistics_ted.service.CrewLeaderOrderService.ItemInput;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Crew-leader "My Orders" surface. A crew leader (role {@code CREW_LEADER}, resolved by username from
 * the worker-credential login) sees only the (house, stage) cells assigned to their crew and raises
 * material orders scoped to those — the ownership check lives in {@link CrewLeaderOrderService}.
 */
@RestController
@RequestMapping("/api/my")
public class MyOrdersController {

    private final CrewLeaderOrderService leaderOrders;

    public MyOrdersController(CrewLeaderOrderService leaderOrders) {
        this.leaderOrders = leaderOrders;
    }

    @GetMapping("/assignments")
    @PreAuthorize("hasRole('CREW_LEADER')")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> assignments() {
        return leaderOrders.assignmentsFor(currentUsername()).stream().map(this::stageDto).toList();
    }

    @GetMapping("/material-orders")
    @PreAuthorize("hasRole('CREW_LEADER')")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> myOrders() {
        return leaderOrders.ordersFor(currentUsername()).stream().map(this::orderDto).toList();
    }

    @PostMapping("/material-orders")
    @PreAuthorize("hasRole('CREW_LEADER')")
    @Transactional
    public ResponseEntity<?> createOrder(@RequestBody Map<String, Object> body) {
        Integer houseStageId = body.get("houseStageId") != null
            ? Integer.valueOf(body.get("houseStageId").toString()) : null;
        String notes = body.get("notes") != null ? body.get("notes").toString() : null;
        MaterialOrder saved = leaderOrders.create(currentUsername(), houseStageId, notes, parseItems(body.get("items")));
        return ResponseEntity.ok(orderDto(saved));
    }

    @PostMapping("/stages/{houseStageId}/start")
    @PreAuthorize("hasRole('CREW_LEADER')")
    @Transactional
    public Map<String, Object> startStage(@PathVariable Integer houseStageId) {
        return stageDto(leaderOrders.startStage(currentUsername(), houseStageId));
    }

    @PostMapping("/stages/{houseStageId}/finish")
    @PreAuthorize("hasRole('CREW_LEADER')")
    @Transactional
    public Map<String, Object> finishStage(@PathVariable Integer houseStageId) {
        return stageDto(leaderOrders.finishStage(currentUsername(), houseStageId));
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : null;
    }

    @SuppressWarnings("unchecked")
    private List<ItemInput> parseItems(Object raw) {
        List<ItemInput> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                Map<String, Object> item = (Map<String, Object>) o;
                Integer materialId = item.get("materialId") != null
                    ? Integer.valueOf(item.get("materialId").toString()) : null;
                BigDecimal qty = item.get("quantity") != null
                    ? new BigDecimal(item.get("quantity").toString()) : null;
                out.add(new ItemInput(materialId, qty));
            }
        }
        return out;
    }

    private Map<String, Object> stageDto(HouseStage s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("houseStageId", s.getId());
        m.put("houseId",      s.getHouse().getId());
        m.put("houseName",    s.getHouse().getName());
        m.put("stageOrder",   s.getStageOrder());
        m.put("stageName",    s.getStageName());
        m.put("stageNameEn",  s.getStageNameEn());
        m.put("status",       s.getStatus());
        m.put("startDate",    s.getStartDate() != null ? s.getStartDate().toString() : null);
        m.put("endDate",      s.getEndDate()   != null ? s.getEndDate().toString()   : null);
        return m;
    }

    private Map<String, Object> orderDto(MaterialOrder o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",           o.getId());
        m.put("houseId",      o.getHouse().getId());
        m.put("houseName",    o.getHouse().getName());
        m.put("houseStageId", o.getHouseStage() != null ? o.getHouseStage().getId() : null);
        m.put("stageName",    o.getHouseStage() != null ? o.getHouseStage().getStageName() : null);
        m.put("status",       o.getStatus());
        m.put("notes",        o.getNotes());
        m.put("createdByName", o.getCreatedBy() != null ? o.getCreatedBy().getName() : null);
        m.put("createdAt",    o.getCreatedAt().toString());
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
