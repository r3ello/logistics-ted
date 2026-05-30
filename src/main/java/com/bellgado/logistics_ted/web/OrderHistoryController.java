package com.bellgado.logistics_ted.web;

import com.bellgado.logistics_ted.service.OrderHistoryService;
import com.bellgado.logistics_ted.service.OrderHistoryService.AccessDeniedSimple;
import com.bellgado.logistics_ted.service.OrderHistoryService.OptionView;
import com.bellgado.logistics_ted.web.dto.OrderHistoryDtos.ChooseRequest;
import com.bellgado.logistics_ted.web.dto.OrderHistoryDtos.OrderDetailDto;
import com.bellgado.logistics_ted.web.dto.OrderHistoryDtos.OrderSummaryDto;
import jakarta.persistence.EntityNotFoundException;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderHistoryController {

    private static final Logger log = LoggerFactory.getLogger(OrderHistoryController.class);

    private final OrderHistoryService history;

    public OrderHistoryController(OrderHistoryService history) {
        this.history = history;
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "20") int size,
                                  @RequestParam(defaultValue = "false") boolean all) {
        Integer userId = OrderHistoryService.currentUserId();
        if (userId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        boolean adminAll = all && OrderHistoryService.currentIsAdmin();
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size)));
        Page<OrderSummaryDto> result = history.history(userId, adminAll, pageable);
        return ResponseEntity.ok(Map.of(
            "items", result.getContent(),
            "page", result.getNumber(),
            "size", result.getSize(),
            "total", result.getTotalElements(),
            "totalPages", result.getTotalPages()
        ));
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<?> detail(@PathVariable UUID orderId) {
        Integer userId = OrderHistoryService.currentUserId();
        if (userId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        try {
            OrderDetailDto detail = history.detail(orderId, userId, OrderHistoryService.currentIsAdmin());
            return ResponseEntity.ok(detail);
        } catch (EntityNotFoundException ex) {
            return ResponseEntity.status(404).body(Map.of("error", "Order not found"));
        } catch (AccessDeniedSimple ex) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }
    }

    @PostMapping("/{orderId}/options/{objective}/view")
    public ResponseEntity<?> view(@PathVariable UUID orderId, @PathVariable String objective) {
        Integer userId = OrderHistoryService.currentUserId();
        if (userId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        try {
            OptionView v = history.recordView(orderId, objective, userId);
            return ResponseEntity.ok(Map.of(
                "ok", true,
                "optionId", v.optionPublicId(),
                "objective", v.objective(),
                "viewCount", v.viewCount()
            ));
        } catch (EntityNotFoundException ex) {
            return ResponseEntity.status(404).body(Map.of("error", "Order or option not found"));
        } catch (RuntimeException ex) {
            log.error("view: failed for order {} objective {}", orderId, objective, ex);
            return ResponseEntity.status(500).body(Map.of("error", "Internal error"));
        }
    }

    @PostMapping("/{orderId}/choose")
    public ResponseEntity<?> choose(@PathVariable UUID orderId, @RequestBody ChooseRequest body) {
        Integer userId = OrderHistoryService.currentUserId();
        if (userId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        if (body == null || body.objective() == null || body.objective().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "objective is required"));
        }
        try {
            OptionView v = history.recordChoice(orderId, body.objective(), userId);
            return ResponseEntity.ok(Map.of(
                "ok", true,
                "orderId", orderId,
                "chosenObjective", v.objective(),
                "optionId", v.optionPublicId()
            ));
        } catch (EntityNotFoundException ex) {
            return ResponseEntity.status(404).body(Map.of("error", "Order or option not found"));
        } catch (RuntimeException ex) {
            log.error("choose: failed for order {} objective {}", orderId, body.objective(), ex);
            return ResponseEntity.status(500).body(Map.of("error", "Internal error"));
        }
    }
}
