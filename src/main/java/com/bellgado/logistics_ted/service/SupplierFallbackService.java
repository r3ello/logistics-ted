package com.bellgado.logistics_ted.service;

import com.bellgado.logistics_ted.domain.SupplierInventory;
import com.bellgado.logistics_ted.repository.SupplierInventoryRepository;
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
 * Phase-5 fallback: after the house allocation finishes, any material still with {@code rem > 0}
 * gets a chance to be filled from {@code supplier_inventory}. Suppliers are not in the house
 * matrix, so this service builds its own small matrix over {@code [anchor, …suppliers, dest]}
 * via the shared {@link RouteMatrixService} so the supplier leg gets the same caching and (when
 * configured) Google road-distance treatment as the house leg.
 *
 * Phase 7 made the picker objective-aware via {@link ObjectiveSpec} so the same "closest
 * supplier" picks track whichever objective the main route was solved for. The returned extras
 * are always in km AND minutes so the caller can add either to its respective total.
 */
@Service
public class SupplierFallbackService {

    private static final Logger log = LoggerFactory.getLogger(SupplierFallbackService.class);

    private static final double MIN_DIST_KM = 0.1;
    private static final String SELECTION_REASON = "supplier_fallback";

    private final SupplierInventoryRepository supplierInventory;
    private final RouteMatrixService matrixService;

    public SupplierFallbackService(SupplierInventoryRepository supplierInventory,
                                   RouteMatrixService matrixService) {
        this.supplierInventory = supplierInventory;
        this.matrixService = matrixService;
    }

    public SupplierFallbackResult coverDeficits(Map<Integer, RemainingNeed> needs,
                                                double anchorLat, double anchorLng,
                                                double destLat, double destLng,
                                                ObjectiveSpec objective) {
        if (needs.isEmpty()) {
            log.debug("supplier-fallback: no remaining needs — skipping");
            return SupplierFallbackResult.empty(needs);
        }

        List<SupplierInventory> rows = supplierInventory.findStocked(needs.keySet());
        if (rows.isEmpty()) {
            log.info("supplier-fallback[{}]: {} material(s) short but NO suppliers stock them — returning unfilled",
                objective.label(), needs.size());
            return SupplierFallbackResult.empty(needs);
        }
        log.info("supplier-fallback[{}]: {} short material(s), {} supplier-inventory row(s) match",
            objective.label(), needs.size(), rows.size());

        Map<Integer, Candidate> bySupplier = new LinkedHashMap<>();
        for (SupplierInventory row : rows) {
            var s = row.getSupplier();
            Candidate c = bySupplier.computeIfAbsent(s.getId(), k -> new Candidate(
                s.getId(), s.getName(), s.getLocation(),
                s.getLat().doubleValue(), s.getLng().doubleValue()
            ));
            var m = row.getMaterial();
            c.stock.put(m.getId(), new MaterialStock(
                m.getName(), m.getUnit(), row.getQuantity().doubleValue()));
        }

        // Matrix: 0 = anchor, 1..N = supplier candidates, N+1 = destination.
        List<double[]> points = new ArrayList<>(bySupplier.size() + 2);
        points.add(new double[]{anchorLat, anchorLng});
        int idx = 1;
        for (Candidate c : bySupplier.values()) {
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

            List<Candidate> providers = bySupplier.values().stream()
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

        List<Candidate> orderedPicks = bySupplier.values().stream()
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

        return new SupplierFallbackResult(stops, remaining, extraKm, extraSeconds);
    }

    public record RemainingNeed(double quantity, String name, String unit) {}

    public record SupplierFallbackResult(
        List<RouteStopDto> stops,
        Map<Integer, RemainingNeed> remaining,
        double extraKm,
        double extraSeconds
    ) {
        public double extraMinutes() {
            return extraSeconds / 60.0;
        }

        public static SupplierFallbackResult empty(Map<Integer, RemainingNeed> needs) {
            return new SupplierFallbackResult(List.of(), new LinkedHashMap<>(needs), 0.0, 0.0);
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
