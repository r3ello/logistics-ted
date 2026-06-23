package com.bellgado.logistics_ted.service;

import com.bellgado.logistics_ted.domain.DepotInventory;
import com.bellgado.logistics_ted.repository.DepotInventoryRepository;
import com.bellgado.logistics_ted.service.SupplierFallbackService.RemainingNeed;
import com.bellgado.logistics_ted.service.distance.RouteCostMatrix;
import com.bellgado.logistics_ted.service.distance.RouteMatrixService;
import com.bellgado.logistics_ted.service.solver.Cost;
import com.bellgado.logistics_ted.service.solver.ObjectiveSpec;
import com.bellgado.logistics_ted.web.dto.OrderResponse.RouteStopDto;
import com.bellgado.logistics_ted.web.dto.OrderResponse.StopContributionDto;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Tier-2 fallback: after the house allocation finishes, any material still short gets a
 * chance to be filled from company-owned warehouses ({@code depot_inventory}) <em>before</em>
 * the external-supplier tier ({@link SupplierFallbackService}). The strict priority
 * house &rarr; warehouse &rarr; supplier is enforced by sequencing — each tier only sees the
 * remainder the previous tier could not cover.
 *
 * <p>Structurally identical to {@link SupplierFallbackService}: builds its own small matrix
 * over {@code [anchor, …depots, dest]} via the shared {@link RouteMatrixService} (so the
 * depot leg gets the same caching and Google-road treatment), is objective-aware, and returns
 * extras in km AND minutes so the caller can add either to its respective total. Kept as a
 * separate class rather than refactoring the supplier service so the supplier path — which is
 * pinned by the route-optimization goldens — stays byte-for-byte unchanged.
 */
@Service
public class DepotFallbackService {

    private static final Logger log = LoggerFactory.getLogger(DepotFallbackService.class);

    private static final double MIN_DIST_KM = 0.1;
    private static final String SELECTION_REASON = "warehouse_fallback";

    private final DepotInventoryRepository depotInventory;
    private final RouteMatrixService matrixService;

    public DepotFallbackService(DepotInventoryRepository depotInventory,
                                RouteMatrixService matrixService) {
        this.depotInventory = depotInventory;
        this.matrixService = matrixService;
    }

    public DepotFallbackResult coverDeficits(Map<Integer, RemainingNeed> needs,
                                             double anchorLat, double anchorLng,
                                             double destLat, double destLng,
                                             ObjectiveSpec objective) {
        if (needs.isEmpty()) {
            log.debug("depot-fallback: no remaining needs — skipping");
            return DepotFallbackResult.empty(needs);
        }

        List<DepotInventory> rows = depotInventory.findStocked(needs.keySet());
        if (rows.isEmpty()) {
            log.info("depot-fallback[{}]: {} material(s) short but NO warehouses stock them — passing through to supplier tier",
                objective.label(), needs.size());
            return DepotFallbackResult.empty(needs);
        }
        log.info("depot-fallback[{}]: {} short material(s), {} depot-inventory row(s) match",
            objective.label(), needs.size(), rows.size());

        Map<Integer, Candidate> byDepot = new LinkedHashMap<>();
        for (DepotInventory row : rows) {
            var d = row.getDepot();
            Candidate c = byDepot.computeIfAbsent(d.getId(), k -> new Candidate(
                d.getId(), d.getName(), d.getLocation(),
                d.getLat().doubleValue(), d.getLng().doubleValue()
            ));
            var m = row.getMaterial();
            c.stock.put(m.getId(), new MaterialStock(
                m.getName(), m.getUnit(), row.getQuantity().doubleValue()));
        }

        // Matrix: 0 = anchor, 1..N = depot candidates, N+1 = destination.
        List<double[]> points = new ArrayList<>(byDepot.size() + 2);
        points.add(new double[]{anchorLat, anchorLng});
        int idx = 1;
        for (Candidate c : byDepot.values()) {
            c.index = idx++;
            points.add(new double[]{c.lat, c.lng});
        }
        int destIdx = idx;
        points.add(new double[]{destLat, destLng});
        RouteCostMatrix matrix = matrixService.compute(points);
        Cost cost = objective.toCost(matrix);

        Map<Integer, Double> remainingByMaterial = new LinkedHashMap<>();
        needs.forEach((mid, n) -> remainingByMaterial.put(mid, n.quantity()));

        Map<Integer, Map<Integer, Double>> picks = new LinkedHashMap<>();

        for (Map.Entry<Integer, RemainingNeed> e : needs.entrySet()) {
            int mid = e.getKey();
            double rem = remainingByMaterial.getOrDefault(mid, 0.0);
            if (rem <= 0) continue;

            List<Candidate> providers = byDepot.values().stream()
                .filter(c -> c.stock.getOrDefault(mid, MaterialStock.NONE).quantity() > 0)
                .sorted(Comparator.comparingDouble(c -> cost.of(0, c.index)))
                .toList();

            for (Candidate c : providers) {
                if (rem <= 0) break;
                MaterialStock s = c.stock.get(mid);
                double available = s.quantity() - picks
                    .getOrDefault(c.id, Map.of())
                    .getOrDefault(mid, 0.0);
                if (available <= 0) continue;
                double take = Math.min(available, rem);
                rem -= take;
                picks.computeIfAbsent(c.id, k -> new LinkedHashMap<>())
                    .merge(mid, take, Double::sum);
            }
            remainingByMaterial.put(mid, rem);
        }

        List<Candidate> orderedPicks = byDepot.values().stream()
            .filter(c -> picks.containsKey(c.id))
            .sorted(Comparator.comparingDouble(c -> cost.of(0, c.index)))
            .toList();

        List<RouteStopDto> stops = new ArrayList<>(orderedPicks.size());
        for (Candidate c : orderedPicks) {
            Map<Integer, StopContributionDto> contributions = new LinkedHashMap<>();
            int distFromAnchor = (int) Math.round(Math.max(matrix.km(0, c.index), MIN_DIST_KM));
            for (Map.Entry<Integer, Double> taken : picks.get(c.id).entrySet()) {
                int mid = taken.getKey();
                MaterialStock s = c.stock.get(mid);
                contributions.put(mid, new StopContributionDto(
                    taken.getValue(),
                    s.name(),
                    s.unit(),
                    SELECTION_REASON,
                    distFromAnchor,
                    s.quantity()
                ));
            }
            stops.add(new RouteStopDto(c.id, c.name, c.location, c.lat, c.lng, contributions));
        }

        double extraKm = 0;
        double extraSeconds = 0;
        if (!orderedPicks.isEmpty()) {
            double directKm = matrix.km(0, destIdx);
            double directSeconds = matrix.seconds(0, destIdx);
            double legKm = 0;
            double legSeconds = 0;
            int prev = 0;
            for (Candidate c : orderedPicks) {
                legKm += matrix.km(prev, c.index);
                legSeconds += matrix.seconds(prev, c.index);
                prev = c.index;
            }
            legKm += matrix.km(prev, destIdx);
            legSeconds += matrix.seconds(prev, destIdx);
            extraKm = Math.max(0.0, legKm - directKm);
            extraSeconds = Math.max(0.0, legSeconds - directSeconds);
        }

        Map<Integer, RemainingNeed> remaining = new LinkedHashMap<>();
        for (Map.Entry<Integer, RemainingNeed> e : needs.entrySet()) {
            int mid = e.getKey();
            double rem = remainingByMaterial.getOrDefault(mid, 0.0);
            if (rem > 0) {
                remaining.put(mid, new RemainingNeed(rem, e.getValue().name(), e.getValue().unit()));
            }
        }

        return new DepotFallbackResult(stops, remaining, extraKm, extraSeconds);
    }

    public record DepotFallbackResult(
        List<RouteStopDto> stops,
        Map<Integer, RemainingNeed> remaining,
        double extraKm,
        double extraSeconds
    ) {
        public double extraMinutes() {
            return extraSeconds / 60.0;
        }

        public static DepotFallbackResult empty(Map<Integer, RemainingNeed> needs) {
            return new DepotFallbackResult(List.of(), new LinkedHashMap<>(needs), 0.0, 0.0);
        }
    }

    private static final class Candidate {
        final int id;
        final String name;
        final String location;
        final double lat;
        final double lng;
        final Map<Integer, MaterialStock> stock = new HashMap<>();
        int index = -1;

        Candidate(int id, String name, String location, double lat, double lng) {
            this.id = id;
            this.name = name;
            this.location = location;
            this.lat = lat;
            this.lng = lng;
        }
    }

    private record MaterialStock(String name, String unit, double quantity) {
        static final MaterialStock NONE = new MaterialStock("", "", 0);
    }
}
