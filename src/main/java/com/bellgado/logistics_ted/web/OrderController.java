package com.bellgado.logistics_ted.web;

import com.bellgado.logistics_ted.service.OrderHistoryService;
import com.bellgado.logistics_ted.service.OrderHistoryService.RecordResult;
import com.bellgado.logistics_ted.service.OrderHistoryService.Source;
import com.bellgado.logistics_ted.service.RouteOptimizationService;
import com.bellgado.logistics_ted.service.RouteOptimizationService.OrderValidationException;
import com.bellgado.logistics_ted.web.dto.OrderRequest;
import com.bellgado.logistics_ted.web.dto.OrderResponse;
import com.bellgado.logistics_ted.web.dto.OrderResponse.RouteOptionDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    private final RouteOptimizationService routes;
    private final OrderHistoryService history;

    public OrderController(RouteOptimizationService routes, OrderHistoryService history) {
        this.routes = routes;
        this.history = history;
    }

    @PostMapping("/calculate-order")
    public ResponseEntity<?> calculate(@RequestBody OrderRequest req) {
        long t0 = System.currentTimeMillis();
        log.info("POST /api/calculate-order start=({},{}) dest={} materials={} lang={}",
            req.startLat(), req.startLng(), req.destinationHouseId(),
            req.materials() == null ? 0 : req.materials().size(), req.lang());
        try {
            OrderResponse result = routes.calculate(req);
            long elapsedMs = System.currentTimeMillis() - t0;
            log.info("POST /api/calculate-order ok in {}ms — alts={} houses={} suppliers={} km={} min={} fulfilled={}",
                elapsedMs,
                result.alternatives() == null ? 0 : result.alternatives().size(),
                result.route() == null ? 0 : result.route().size(),
                result.supplierStops() == null ? 0 : result.supplierStops().size(),
                result.totalDistance(), result.totalMinutes(), result.fullyFulfilled());

            OrderResponse withHistory = attachHistory(req, result, elapsedMs);
            return ResponseEntity.ok(withHistory);
        } catch (OrderValidationException ex) {
            log.warn("POST /api/calculate-order rejected: {}", ex.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        } catch (RuntimeException ex) {
            log.error("POST /api/calculate-order failed in {}ms", System.currentTimeMillis() - t0, ex);
            throw ex;
        }
    }

    /**
     * Persist the calculation and decorate the response with the new IDs. History failures
     * are logged but never escalated — the caller still gets a valid route.
     */
    private OrderResponse attachHistory(OrderRequest req, OrderResponse result, long elapsedMs) {
        try {
            Integer userId = OrderHistoryService.currentUserId();
            RecordResult rec = history.recordCalculation(req, result, Source.DASHBOARD,
                userId, null, elapsedMs);
            List<RouteOptionDto> withIds = new ArrayList<>();
            for (RouteOptionDto alt : result.alternatives()) {
                UUID optId = rec.optionPublicIds().get(alt.objective());
                withIds.add(optId == null ? alt : alt.withOptionId(optId));
            }
            return result.withIds(rec.orderPublicId(), withIds);
        } catch (RuntimeException ex) {
            log.warn("history: failed to persist order — returning route without IDs", ex);
            return result;
        }
    }
}
