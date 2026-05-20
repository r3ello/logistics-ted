package com.bellgado.logistics_ted.service.distance;

import com.bellgado.logistics_ted.config.RoutingProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * {@link RouteMatrixService} backed by Google Routes API
 * {@code distanceMatrix/v2:computeRouteMatrix}. Phase 7: captures both distance and duration —
 * solver decides which to optimise via {@code ObjectiveSpec}.
 *
 * On any failure — network error, non-2xx, malformed payload, or even one missing cell — falls
 * back to the injected delegate so /api/calculate-order stays online. Test seam:
 * {@link #callRoutes(List)} can be overridden so unit tests don't need WireMock.
 */
public class GoogleRoutesMatrixService implements RouteMatrixService {

    private static final Logger log = LoggerFactory.getLogger(GoogleRoutesMatrixService.class);

    private static final String FIELD_MASK =
        "originIndex,destinationIndex,distanceMeters,duration,condition";

    private final RestClient client;
    private final RoutingProperties.Google config;
    private final RouteMatrixService fallback;

    public GoogleRoutesMatrixService(RestClient client,
                                     RoutingProperties.Google config,
                                     RouteMatrixService fallback) {
        this.client = client;
        this.config = config;
        this.fallback = fallback;
    }

    @Override
    public RouteCostMatrix compute(List<double[]> points) {
        if (config.apiKey() == null || config.apiKey().isBlank()) {
            log.warn("Google Routes API key not configured; falling back to haversine for {} points.", points.size());
            return fallback.compute(points);
        }
        long t0 = System.currentTimeMillis();
        try {
            log.info("Google Routes: requesting {}x{} matrix ({} pairs)",
                points.size(), points.size(), points.size() * (points.size() - 1));
            List<MatrixElement> elements = callRoutes(points);
            RouteCostMatrix matrix = assembleMatrix(elements, points.size());
            log.info("Google Routes: matrix complete in {}ms ({} elements returned)",
                System.currentTimeMillis() - t0, elements.size());
            return matrix;
        } catch (Exception e) {
            log.warn("Google Routes failed for {} points after {}ms; falling back to haversine: {}",
                points.size(), System.currentTimeMillis() - t0, e.toString());
            return fallback.compute(points);
        }
    }

    protected List<MatrixElement> callRoutes(List<double[]> points) {
        List<Map<String, Object>> waypoints = points.stream()
            .map(p -> Map.<String, Object>of(
                "waypoint", Map.of(
                    "location", Map.of(
                        "latLng", Map.of("latitude", p[0], "longitude", p[1])))))
            .toList();

        Map<String, Object> body = Map.of(
            "origins", waypoints,
            "destinations", waypoints,
            "travelMode", config.travelMode(),
            "routingPreference", config.routingPreference());

        List<MatrixElement> response = client.post()
            .uri("/distanceMatrix/v2:computeRouteMatrix")
            .header("X-Goog-Api-Key", config.apiKey())
            .header("X-Goog-FieldMask", FIELD_MASK)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .body(new ParameterizedTypeReference<>() {});

        return response == null ? List.of() : response;
    }

    static RouteCostMatrix assembleMatrix(List<MatrixElement> elements, int n) {
        RouteCost[][] cells = new RouteCost[n][n];
        boolean[][] filled = new boolean[n][n];
        for (int i = 0; i < n; i++) {
            cells[i][i] = RouteCost.ZERO;
            filled[i][i] = true;
        }

        for (MatrixElement e : elements) {
            if (e.originIndex() < 0 || e.originIndex() >= n
                || e.destinationIndex() < 0 || e.destinationIndex() >= n) {
                continue;
            }
            if (!"ROUTE_EXISTS".equals(e.condition()) || e.distanceMeters() == null) {
                continue;
            }
            double km = e.distanceMeters() / 1000.0;
            double seconds = parseSeconds(e.duration());
            cells[e.originIndex()][e.destinationIndex()] = new RouteCost(km, seconds);
            filled[e.originIndex()][e.destinationIndex()] = true;
        }

        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (!filled[i][j]) {
                    List<int[]> missing = listMissing(filled, n);
                    throw new IncompleteMatrixException(
                        "Google Routes returned no usable distance for "
                            + missing.size() + " of " + (n * n - n) + " off-diagonal cells; first: "
                            + Arrays.toString(missing.get(0)));
                }
            }
        }
        return new RouteCostMatrix(cells);
    }

    private static double parseSeconds(String duration) {
        if (duration == null || duration.isBlank()) return 0;
        String trimmed = duration.endsWith("s")
            ? duration.substring(0, duration.length() - 1)
            : duration;
        try {
            return Double.parseDouble(trimmed);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static List<int[]> listMissing(boolean[][] filled, int n) {
        List<int[]> missing = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (!filled[i][j]) missing.add(new int[]{i, j});
            }
        }
        return missing;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MatrixElement(
        int originIndex,
        int destinationIndex,
        Long distanceMeters,
        String duration,
        String condition
    ) {}

    static final class IncompleteMatrixException extends RuntimeException {
        IncompleteMatrixException(String message) { super(message); }
    }
}
