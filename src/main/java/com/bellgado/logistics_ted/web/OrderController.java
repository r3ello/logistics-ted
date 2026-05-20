package com.bellgado.logistics_ted.web;

import com.bellgado.logistics_ted.service.RouteOptimizationService;
import com.bellgado.logistics_ted.service.RouteOptimizationService.OrderValidationException;
import com.bellgado.logistics_ted.web.dto.OrderRequest;
import com.bellgado.logistics_ted.web.dto.OrderResponse;
import java.util.Map;
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

    public OrderController(RouteOptimizationService routes) {
        this.routes = routes;
    }

    @PostMapping("/calculate-order")
    public ResponseEntity<?> calculate(@RequestBody OrderRequest req) {
        long t0 = System.currentTimeMillis();
        log.info("POST /api/calculate-order start=({},{}) dest={} materials={} lang={}",
            req.startLat(), req.startLng(), req.destinationHouseId(),
            req.materials() == null ? 0 : req.materials().size(), req.lang());
        try {
            OrderResponse result = routes.calculate(req);
            log.info("POST /api/calculate-order ok in {}ms — alts={} houses={} suppliers={} km={} min={} fulfilled={}",
                System.currentTimeMillis() - t0,
                result.alternatives() == null ? 0 : result.alternatives().size(),
                result.route() == null ? 0 : result.route().size(),
                result.supplierStops() == null ? 0 : result.supplierStops().size(),
                result.totalDistance(), result.totalMinutes(), result.fullyFulfilled());
            return ResponseEntity.ok(result);
        } catch (OrderValidationException ex) {
            log.warn("POST /api/calculate-order rejected: {}", ex.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        } catch (RuntimeException ex) {
            log.error("POST /api/calculate-order failed in {}ms", System.currentTimeMillis() - t0, ex);
            throw ex;
        }
    }
}
