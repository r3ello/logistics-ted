package com.bellgado.logistics_ted.service;

import com.bellgado.logistics_ted.config.RoutingProperties;
import com.bellgado.logistics_ted.domain.House;
import com.bellgado.logistics_ted.domain.Inventory;
import com.bellgado.logistics_ted.domain.Material;
import com.bellgado.logistics_ted.repository.HouseRepository;
import com.bellgado.logistics_ted.repository.InventoryRepository;
import com.bellgado.logistics_ted.repository.MaterialRepository;
import com.bellgado.logistics_ted.service.DepotFallbackService.DepotFallbackResult;
import com.bellgado.logistics_ted.service.SupplierFallbackService.RemainingNeed;
import com.bellgado.logistics_ted.service.SupplierFallbackService.SupplierFallbackResult;
import com.bellgado.logistics_ted.service.distance.RouteCostMatrix;
import com.bellgado.logistics_ted.service.distance.RouteMatrixService;
import com.bellgado.logistics_ted.service.solver.CandidateStop;
import com.bellgado.logistics_ted.service.solver.Cost;
import com.bellgado.logistics_ted.service.solver.InventoryEntry;
import com.bellgado.logistics_ted.service.solver.ObjectiveSpec;
import com.bellgado.logistics_ted.service.solver.RouteSolver;
import com.bellgado.logistics_ted.service.solver.SolveInput;
import com.bellgado.logistics_ted.service.solver.SolveResult;
import com.bellgado.logistics_ted.web.dto.OrderRequest;
import com.bellgado.logistics_ted.web.dto.OrderResponse;
import com.bellgado.logistics_ted.web.dto.OrderResponse.DeficitDto;
import com.bellgado.logistics_ted.web.dto.OrderResponse.LocationDto;
import com.bellgado.logistics_ted.web.dto.OrderResponse.RouteOptionDto;
import com.bellgado.logistics_ted.web.dto.OrderResponse.RouteStopDto;
import com.bellgado.logistics_ted.web.dto.OrderResponse.StopContributionDto;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates {@code POST /api/calculate-order}: load candidates, build the matrix once, then
 * run the solver three times — shortest-distance, fastest-time, balanced — collecting the
 * results as deduplicated {@link RouteOptionDto}s. The top-level fields mirror the
 * shortest-distance option so today's frontend keeps working unchanged; the new
 * {@code alternatives} list exposes all options for the dispatcher UI uplift to come.
 */
@Service
public class RouteOptimizationService {

    private static final Logger log = LoggerFactory.getLogger(RouteOptimizationService.class);

    private static final double MIN_DIST_KM = 0.1;
    private static final int ORIGIN_IDX = 0;

    private final HouseRepository houses;
    private final InventoryRepository inventories;
    private final MaterialRepository materials;
    private final ServerMessages messages;
    private final RouteMatrixService matrixService;
    private final DepotFallbackService depotFallback;
    private final SupplierFallbackService supplierFallback;
    private final RouteSolver routeSolver;
    private final RoutingProperties properties;

    public RouteOptimizationService(HouseRepository houses, InventoryRepository inventories,
                                    MaterialRepository materials, ServerMessages messages,
                                    RouteMatrixService matrixService,
                                    DepotFallbackService depotFallback,
                                    SupplierFallbackService supplierFallback,
                                    RouteSolver routeSolver,
                                    RoutingProperties properties) {
        this.houses = houses;
        this.inventories = inventories;
        this.materials = materials;
        this.messages = messages;
        this.matrixService = matrixService;
        this.depotFallback = depotFallback;
        this.supplierFallback = supplierFallback;
        this.routeSolver = routeSolver;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public OrderResponse calculate(OrderRequest req) {
        String lang = req.lang() == null ? "en" : req.lang();

        if (req.startLat() == null || req.startLng() == null) {
            throw new OrderValidationException(messages.get(lang, "noStartHouse"));
        }
        if (req.destinationHouseId() == null) {
            throw new OrderValidationException(messages.get(lang, "noDestHouse"));
        }
        if (req.materials() == null || req.materials().isEmpty()) {
            throw new OrderValidationException(messages.get(lang, "noValidQty"));
        }

        House dest = houses.findById(req.destinationHouseId()).orElse(null);
        if (dest == null || dest.getLat() == null || dest.getLng() == null) {
            throw new OrderValidationException(messages.get(lang, "houseNotFound"));
        }

        Map<Integer, Double> needed = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : req.materials().entrySet()) {
            try {
                double n = Double.parseDouble(String.valueOf(e.getValue()));
                if (n > 0) needed.put(Integer.parseInt(e.getKey()), n);
            } catch (NumberFormatException ignore) {
                // skip non-numeric entries, matching the Node behavior
            }
        }
        if (needed.isEmpty()) {
            throw new OrderValidationException(messages.get(lang, "noValidQty"));
        }

        Map<Integer, Material> materialIndex = new HashMap<>();
        for (Material m : materials.findAllByOrderByIdAsc()) materialIndex.put(m.getId(), m);

        double originLat = req.startLat();
        double originLng = req.startLng();
        String originName = req.startName() == null || req.startName().isBlank() ? "Driver location" : req.startName();
        String originLocation = String.format(Locale.ROOT, "%.5f, %.5f", originLat, originLng);

        List<CandidateStop> all = buildCandidates(req.destinationHouseId());
        List<CandidateStop> relevant = all.stream()
            .filter(h -> needed.keySet().stream().anyMatch(mid -> h.qtyOf(mid) > 0))
            .toList();

        log.info("calculate: needed materials={}, candidate houses total={}, relevant={} (dest={} '{}')",
            needed, all.size(), relevant.size(), dest.getId(), dest.getName());
        if (log.isDebugEnabled()) {
            log.debug("calculate: relevant ids={}", relevant.stream().map(CandidateStop::id).toList());
        }
        if (relevant.isEmpty()) {
            log.warn("calculate: no relevant candidate houses found — all objectives will produce an empty route and dedupe to a single alternative");
        }

        // Matrix layout: 0 = origin, 1..relevant.size() = relevant houses, last = destination.
        List<double[]> points = new ArrayList<>(relevant.size() + 2);
        points.add(new double[]{originLat, originLng});
        for (int i = 0; i < relevant.size(); i++) {
            CandidateStop h = relevant.get(i);
            h.setIndex(i + 1);
            points.add(new double[]{h.lat(), h.lng()});
        }
        int destIdx = relevant.size() + 1;
        points.add(new double[]{dest.getLat().doubleValue(), dest.getLng().doubleValue()});
        long tMatrix = System.currentTimeMillis();
        RouteCostMatrix matrix = matrixService.compute(points);
        log.info("calculate: matrix built {}x{} via {} in {}ms (provider={})",
            points.size(), points.size(),
            matrixService.getClass().getSimpleName(),
            System.currentTimeMillis() - tMatrix,
            properties.provider());

        LocationDto originDto = new LocationDto("gps", originName, originLocation);
        LocationDto destDto = new LocationDto(dest.getId(), dest.getName(), dest.getLocation());

        List<ObjectiveSpec> objectives = List.of(
            ObjectiveSpec.shortestDistance(),
            ObjectiveSpec.fastestTime(),
            ObjectiveSpec.balanced(properties.balanced().alpha(), properties.balanced().beta()),
            ObjectiveSpec.housesAndWarehousesOnly(),
            ObjectiveSpec.housesAndSuppliersOnly()
        );

        // Phase 1: solve each objective independently. Per the heuristic 2-opt limitation,
        // these can land in different local minima — the route labelled "fastest_time" is not
        // guaranteed to actually be the fastest.
        List<RouteOptionDto> rawResults = new ArrayList<>(objectives.size());
        for (ObjectiveSpec spec : objectives) {
            long tSolve = System.currentTimeMillis();
            RouteOptionDto opt = buildOption(spec, originLat, originLng, dest, destIdx,
                relevant, needed, materialIndex, matrix);
            log.info("objective={} solved in {}ms — km={} min={} houses={} suppliers={} fulfilled={} deficits={} sig={}",
                spec.label(),
                System.currentTimeMillis() - tSolve,
                opt.totalDistance(), opt.totalMinutes(),
                opt.route().size(), opt.supplierStops().size(),
                opt.fullyFulfilled(), opt.deficit().size(),
                signature(spec.label(), opt));
            rawResults.add(opt);
        }

        // Phase 2: Pareto-relabel. For each label, pick whichever of the N candidate routes
        // scores lowest under that label's objective. Guarantees "fastest_time" really has the
        // smallest minutes among the candidates and "shortest_distance" really has the smallest km.
        List<RouteOptionDto> relabeled = new ArrayList<>(objectives.size());
        for (int i = 0; i < objectives.size(); i++) {
            ObjectiveSpec spec = objectives.get(i);
            // Source-type objectives keep their own solved route — relabeling across source modes
            // would assign a supplier route to a warehouse-only card (or vice versa).
            if (!spec.useDepots() || !spec.useSuppliers()) {
                relabeled.add(rawResults.get(i));
                continue;
            }
            // Start from the route that was originally solved for this objective so ties resolve
            // in favour of the natural assignment (no spurious relabels when scores match).
            int bestIdx = i;
            double bestScore = scoreRoute(rawResults.get(i), spec);
            // Only consider routes from objectives with the same source mode (all tiers enabled).
            for (int j = 0; j < rawResults.size(); j++) {
                if (j == i) continue;
                ObjectiveSpec other = objectives.get(j);
                if (!other.useDepots() || !other.useSuppliers()) continue;
                double s = scoreRoute(rawResults.get(j), spec);
                if (s < bestScore) { bestScore = s; bestIdx = j; }
            }
            RouteOptionDto winner = rawResults.get(bestIdx);
            if (bestIdx != i) {
                log.info("relabel: '{}' takes the route originally solved by '{}' (score {} < own {} under {})",
                    spec.label(), objectives.get(bestIdx).label(),
                    String.format(Locale.ROOT, "%.2f", bestScore),
                    String.format(Locale.ROOT, "%.2f", scoreRoute(rawResults.get(i), spec)),
                    spec.label());
                winner = new RouteOptionDto(
                    spec.label(),
                    winner.route(), winner.warehouseStops(), winner.supplierStops(),
                    winner.deficit(), winner.mapsUrl(),
                    winner.fullyFulfilled(), winner.totalStops(),
                    winner.totalDistance(), winner.totalMinutes()
                );
            }
            relabeled.add(winner);
        }

        // Phase 3: dedup by stop signature (label intentionally not in the signature, so two
        // labels pointing to the same stop sequence collapse into one alternative).
        List<RouteOptionDto> alternatives = new ArrayList<>();
        Map<String, String> signatureToObjective = new LinkedHashMap<>();
        Set<String> seenSignatures = new LinkedHashSet<>();
        for (RouteOptionDto opt : relabeled) {
            String sig = signature(opt.objective(), opt);
            boolean added = seenSignatures.add(sig);
            log.info("dedup: alt={} sig={} {}",
                opt.objective(), sig,
                added ? "(kept)" : "(duplicate of " + signatureToObjective.get(sig) + ", dropped)");
            if (added) {
                signatureToObjective.put(sig, opt.objective());
                alternatives.add(opt);
            }
        }
        log.info("calculate: {} unique alternative(s) after dedup — {}", alternatives.size(),
            alternatives.stream().map(RouteOptionDto::objective).toList());
        if (alternatives.size() == 1) {
            log.info("calculate: only one alternative — frontend will hide the alternative cards row (alts.length > 1 is false)");
        }

        // Top-level mirrors the first option, which is always shortest_distance.
        RouteOptionDto primary = alternatives.get(0);

        return new OrderResponse(
            originDto,
            destDto,
            primary.route(),
            primary.warehouseStops(),
            primary.supplierStops(),
            primary.deficit(),
            primary.mapsUrl(),
            primary.fullyFulfilled(),
            primary.totalStops(),
            primary.totalDistance(),
            primary.totalMinutes(),
            alternatives
        );
    }

    private RouteOptionDto buildOption(ObjectiveSpec spec,
                                       double originLat, double originLng,
                                       House dest, int destIdx,
                                       List<CandidateStop> relevant,
                                       Map<Integer, Double> needed,
                                       Map<Integer, Material> materialIndex,
                                       RouteCostMatrix matrix) {
        SolveResult solved = routeSolver.solve(new SolveInput(
            ORIGIN_IDX, destIdx, relevant, needed, matrix, spec));
        List<CandidateStop> orderedStops = solved.orderedStops();
        Cost cost = spec.toCost(matrix);
        if (log.isDebugEnabled()) {
            log.debug("[{}] solver picked {} houses: {}",
                spec.label(), orderedStops.size(),
                orderedStops.stream().map(s -> s.id() + "(" + s.name() + ")").toList());
        }

        Map<Integer, Map<Integer, StopContributionDto>> contributions = new HashMap<>();
        Map<Integer, RemainingNeed> remainingNeeds = new LinkedHashMap<>();
        String selectionReason = orderedStops.size() == 1 ? "optimal_single" : "optimal_multi";

        for (Map.Entry<Integer, Double> e : needed.entrySet()) {
            int mid = e.getKey();
            double rem = e.getValue();
            List<CandidateStop> providers = orderedStops.stream()
                .filter(h -> h.qtyOf(mid) > 0)
                .sorted(Comparator.comparingDouble(h -> cost.of(ORIGIN_IDX, h.index())))
                .toList();

            for (CandidateStop h : providers) {
                if (rem <= 0) break;
                double avail = h.qtyOf(mid);
                double take = Math.min(avail, rem);
                rem -= take;
                InventoryEntry entry = h.inventory().get(mid);
                contributions.computeIfAbsent(h.id(), k -> new LinkedHashMap<>()).put(mid,
                    new StopContributionDto(
                        take,
                        entry.name(),
                        entry.unit(),
                        selectionReason,
                        (int) Math.round(Math.max(matrix.km(ORIGIN_IDX, h.index()), MIN_DIST_KM)),
                        avail
                    ));
            }
            if (rem > 0) {
                Material mat = materialIndex.get(mid);
                if (mat != null) {
                    remainingNeeds.put(mid, new RemainingNeed(rem, mat.getName(), mat.getUnit()));
                }
            }
        }

        double destLatD = dest.getLat().doubleValue();
        double destLngD = dest.getLng().doubleValue();

        // Tier-2: company warehouses (depots) cover what the houses couldn't. Anchored at the
        // truck's current position (last house, or origin if no houses were picked).
        double houseAnchorLat = orderedStops.isEmpty() ? originLat
            : orderedStops.get(orderedStops.size() - 1).lat();
        double houseAnchorLng = orderedStops.isEmpty() ? originLng
            : orderedStops.get(orderedStops.size() - 1).lng();
        if (!remainingNeeds.isEmpty()) {
            log.info("[{}] house allocation left {} material(s) short — invoking warehouse fallback (anchor=({},{}))",
                spec.label(), remainingNeeds.size(), houseAnchorLat, houseAnchorLng);
        }
        DepotFallbackResult depotResult = spec.useDepots()
            ? depotFallback.coverDeficits(remainingNeeds, houseAnchorLat, houseAnchorLng, destLatD, destLngD, spec)
            : DepotFallbackResult.empty(remainingNeeds);
        if (spec.useDepots() && !remainingNeeds.isEmpty()) {
            log.info("[{}] warehouse fallback: {} stops, +{}km +{}s extra, remaining deficits={}",
                spec.label(), depotResult.stops().size(),
                Math.round(depotResult.extraKm()), Math.round(depotResult.extraSeconds()),
                depotResult.remaining().size());
        }

        // Tier-3: external suppliers cover whatever is still short, anchored after the depot
        // leg (last depot, else last house, else origin). This anchor chaining is what makes
        // the per-tier extra-km deltas telescope into one origin→houses→depots→suppliers→dest leg.
        double supplierAnchorLat;
        double supplierAnchorLng;
        if (!depotResult.stops().isEmpty()) {
            RouteStopDto lastDepot = depotResult.stops().get(depotResult.stops().size() - 1);
            supplierAnchorLat = lastDepot.lat();
            supplierAnchorLng = lastDepot.lng();
        } else {
            supplierAnchorLat = houseAnchorLat;
            supplierAnchorLng = houseAnchorLng;
        }
        Map<Integer, RemainingNeed> afterDepot = depotResult.remaining();
        SupplierFallbackResult fallback = spec.useSuppliers()
            ? supplierFallback.coverDeficits(afterDepot, supplierAnchorLat, supplierAnchorLng, destLatD, destLngD, spec)
            : SupplierFallbackResult.empty(afterDepot);
        if (spec.useSuppliers() && !afterDepot.isEmpty()) {
            log.info("[{}] supplier fallback: {} stops, +{}km +{}s extra, remaining deficits={}",
                spec.label(), fallback.stops().size(),
                Math.round(fallback.extraKm()), Math.round(fallback.extraSeconds()),
                fallback.remaining().size());
        }

        List<DeficitDto> deficits = new ArrayList<>();
        fallback.remaining().forEach((mid, need) ->
            deficits.add(new DeficitDto(need.quantity(), need.name(), need.unit())));

        List<RouteStopDto> routeDto = orderedStops.stream()
            .map(h -> new RouteStopDto(h.id(), h.name(), h.location(), h.lat(), h.lng(),
                contributions.getOrDefault(h.id(), Map.of())))
            .toList();

        List<double[]> waypoints = new ArrayList<>();
        waypoints.add(new double[]{originLat, originLng});
        for (CandidateStop s : orderedStops) waypoints.add(new double[]{s.lat(), s.lng()});
        for (RouteStopDto s : depotResult.stops()) waypoints.add(new double[]{s.lat(), s.lng()});
        for (RouteStopDto s : fallback.stops()) waypoints.add(new double[]{s.lat(), s.lng()});
        waypoints.add(new double[]{destLatD, destLngD});

        String mapsUrl = waypoints.size() >= 2
            ? "https://www.google.com/maps/dir/" + waypoints.stream()
                .map(p -> p[0] + "," + p[1])
                .collect(Collectors.joining("/"))
            : "https://www.google.com/maps/search/?api=1&query=" + originLat + "," + originLng;

        double houseKm = 0;
        double houseSeconds = 0;
        int prev = ORIGIN_IDX;
        for (CandidateStop s : orderedStops) {
            houseKm += matrix.km(prev, s.index());
            houseSeconds += matrix.seconds(prev, s.index());
            prev = s.index();
        }
        houseKm += matrix.km(prev, destIdx);
        houseSeconds += matrix.seconds(prev, destIdx);

        long totalDistance = Math.round(houseKm + depotResult.extraKm() + fallback.extraKm());
        long totalMinutes = Math.round(
            (houseSeconds + depotResult.extraSeconds() + fallback.extraSeconds()) / 60.0);

        return new RouteOptionDto(
            spec.label(),
            routeDto,
            depotResult.stops(),
            fallback.stops(),
            deficits,
            mapsUrl,
            deficits.isEmpty(),
            routeDto.size(),
            totalDistance,
            totalMinutes
        );
    }

    /**
     * Scores a candidate route under a given objective using the route's already-aggregated
     * km/minutes totals. Used by Phase 2 (Pareto-relabel) so each label points at the
     * candidate route that genuinely wins under that label's cost.
     */
    private static double scoreRoute(RouteOptionDto opt, ObjectiveSpec spec) {
        double km = opt.totalDistance();
        double min = opt.totalMinutes();
        return switch (spec.type()) {
            case SHORTEST_DISTANCE, HOUSES_WAREHOUSES_ONLY, HOUSES_SUPPLIERS_ONLY -> km;
            case FASTEST_TIME -> min;
            case BALANCED -> spec.alpha() * km + spec.beta() * min;
        };
    }

    private static String signature(String label, RouteOptionDto opt) {
        // Source-type objectives always get a unique signature so they appear as separate cards
        // even when the stop sequence happens to match another objective.
        boolean pinLabel = label.equals("houses_warehouses_only") || label.equals("houses_suppliers_only")
            || label.equals("shortest_distance") || label.equals("fastest_time");
        StringBuilder sb = new StringBuilder();
        if (pinLabel) sb.append(label).append(':');
        sb.append("H[");
        for (RouteStopDto s : opt.route()) sb.append(s.id()).append(',');
        sb.append("]W[");
        for (RouteStopDto s : opt.warehouseStops()) sb.append(s.id()).append(',');
        sb.append("]S[");
        for (RouteStopDto s : opt.supplierStops()) sb.append(s.id()).append(',');
        sb.append("]");
        return sb.toString();
    }

    private List<CandidateStop> buildCandidates(Integer destinationHouseId) {
        Map<Integer, CandidateStop> byHouse = new LinkedHashMap<>();
        for (Inventory inv : inventories.findCandidatesForOrder(destinationHouseId)) {
            House h = inv.getWarehouse().getHouse();
            CandidateStop c = byHouse.computeIfAbsent(h.getId(), k -> new CandidateStop(
                h.getId(), h.getName(), h.getLocation(),
                h.getLat().doubleValue(), h.getLng().doubleValue()
            ));
            Material m = inv.getMaterial();
            c.inventory().put(m.getId(),
                new InventoryEntry(m.getName(), m.getUnit(), inv.getQuantity().doubleValue()));
        }
        return new ArrayList<>(byHouse.values());
    }

    public static final class OrderValidationException extends RuntimeException {
        public OrderValidationException(String message) { super(message); }
    }
}
