package com.bellgado.logistics_ted.service.solver;

import com.bellgado.logistics_ted.service.distance.RouteCostMatrix;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The original Node POC's selection + ordering logic, ported and matrix-driven. Phase 7 swapped
 * direct {@code matrix.km()} reads for a {@link Cost} function from the {@link ObjectiveSpec},
 * so the same algorithm covers shortest-distance, fastest-time, and balanced objectives.
 *
 *   - Exhaustive subset enumeration for N ≤ 15: every covering subset is scored as
 *     {@code origin → NN+2-opt(subset) → destination}; cheapest wins.
 *   - Greedy fallback for N &gt; 15: per material, rank candidates by
 *     {@code quantity / max(cost(origin, h), 0.1)} and pick until demand is met.
 *   - 2-opt is asymmetric-safe (re-scores the reversed interior).
 */
public final class HeuristicRouteSolver implements RouteSolver {

    private static final Logger log = LoggerFactory.getLogger(HeuristicRouteSolver.class);

    private static final int EXHAUSTIVE_LIMIT = 15;
    private static final double MIN_COST = 0.1;

    @Override
    public SolveResult solve(SolveInput input) {
        List<CandidateStop> candidates = input.candidates();
        Map<Integer, Double> needed = input.needed();
        Cost cost = input.cost();
        int originIdx = input.originIdx();
        int destIdx = input.destIdx();

        int n = candidates.size();
        if (n == 0) {
            log.debug("solver: no candidates — returning empty");
            return SolveResult.empty();
        }

        List<CandidateStop> orderedStops;

        if (n <= EXHAUSTIVE_LIMIT) {
            log.debug("solver: exhaustive mode for n={} candidates (2^n-1 = {} subsets to consider)",
                n, (1 << n) - 1);
            double bestDist = Double.POSITIVE_INFINITY;
            int coveringSubsets = 0;
            orderedStops = List.of();
            int upper = 1 << n;
            for (int mask = 1; mask < upper; mask++) {
                List<CandidateStop> subset = new ArrayList<>();
                for (int i = 0; i < n; i++) if ((mask & (1 << i)) != 0) subset.add(candidates.get(i));
                if (!coversDemand(subset, needed)) continue;
                coveringSubsets++;

                List<CandidateStop> nn = nearestNeighborFrom(originIdx, subset, cost);
                List<CandidateStop> ord = twoOpt(nn, originIdx, cost);
                double d = routeCost(originIdx, ord, destIdx, cost);
                if (d < bestDist) {
                    bestDist = d;
                    orderedStops = ord;
                }
            }
            log.debug("solver: exhaustive done — {} covering subset(s), bestCost={}, picked {} stop(s)",
                coveringSubsets, bestDist == Double.POSITIVE_INFINITY ? "n/a" : String.format("%.2f", bestDist),
                orderedStops.size());

            // Partial-coverage fallback: no subset fully covers demand (total stock < order).
            // Re-run the greedy selection to collect as much as possible and report the deficit.
            if (orderedStops.isEmpty()) {
                log.debug("solver: exhaustive found no full-cover subset — falling back to greedy partial selection");
                Set<Integer> pickedIds = new HashSet<>();
                for (Map.Entry<Integer, Double> e : needed.entrySet()) {
                    int mid = e.getKey();
                    double rem = e.getValue();
                    List<CandidateStop> sorted = candidates.stream()
                        .filter(h -> h.qtyOf(mid) > 0)
                        .sorted(Comparator.comparingDouble((CandidateStop h) ->
                            -(h.qtyOf(mid) / Math.max(cost.of(originIdx, h.index()), MIN_COST))))
                        .toList();
                    for (CandidateStop h : sorted) {
                        if (rem <= 0) break;
                        pickedIds.add(h.id());
                        rem -= h.qtyOf(mid);
                    }
                }
                List<CandidateStop> selected = candidates.stream()
                    .filter(h -> pickedIds.contains(h.id()))
                    .toList();
                orderedStops = twoOpt(nearestNeighborFrom(originIdx, selected, cost), originIdx, cost);
                log.debug("solver: greedy partial fallback selected {} stop(s)", orderedStops.size());
            }
        } else {
            log.debug("solver: greedy mode for n={} candidates (> {} exhaustive limit)", n, EXHAUSTIVE_LIMIT);
            Set<Integer> pickedIds = new HashSet<>();
            for (Map.Entry<Integer, Double> e : needed.entrySet()) {
                int mid = e.getKey();
                double rem = e.getValue();
                List<CandidateStop> sorted = candidates.stream()
                    .filter(h -> h.qtyOf(mid) > 0)
                    .sorted(Comparator.comparingDouble((CandidateStop h) ->
                        -(h.qtyOf(mid) / Math.max(cost.of(originIdx, h.index()), MIN_COST))))
                    .toList();
                for (CandidateStop h : sorted) {
                    if (rem <= 0) break;
                    pickedIds.add(h.id());
                    rem -= h.qtyOf(mid);
                }
            }
            List<CandidateStop> selected = candidates.stream()
                .filter(h -> pickedIds.contains(h.id()))
                .toList();
            orderedStops = twoOpt(nearestNeighborFrom(originIdx, selected, cost), originIdx, cost);
        }

        return new SolveResult(orderedStops);
    }

    static boolean coversDemand(List<CandidateStop> subset, Map<Integer, Double> needed) {
        for (Map.Entry<Integer, Double> e : needed.entrySet()) {
            double have = 0;
            for (CandidateStop h : subset) have += h.qtyOf(e.getKey());
            if (have < e.getValue()) return false;
        }
        return true;
    }

    static List<CandidateStop> nearestNeighborFrom(int originIdx, List<CandidateStop> stops,
                                                   Cost cost) {
        if (stops.isEmpty()) return new ArrayList<>();
        List<CandidateStop> route = new ArrayList<>();
        Set<Integer> visited = new HashSet<>();
        int currentIdx = originIdx;

        while (route.size() < stops.size()) {
            CandidateStop nearest = null;
            double minDist = Double.POSITIVE_INFINITY;
            for (CandidateStop s : stops) {
                if (visited.contains(s.id())) continue;
                double d = cost.of(currentIdx, s.index());
                if (d < minDist) { minDist = d; nearest = s; }
            }
            if (nearest == null) break;
            visited.add(nearest.id());
            route.add(nearest);
            currentIdx = nearest.index();
        }
        return route;
    }

    public static List<CandidateStop> twoOpt(List<CandidateStop> route, int originIdx, Cost cost) {
        if (route.size() <= 1) return route;

        int[] path = new int[route.size() + 1];
        path[0] = originIdx;
        for (int k = 0; k < route.size(); k++) path[k + 1] = route.get(k).index();

        if (path.length <= 3) return route;

        // Asymmetric-safe: when cost(a,b) != cost(b,a) the reversed interior edges contribute a
        // different total. For symmetric matrices the interior cancels and this reduces to the
        // two-edge form.
        boolean improved = true;
        while (improved) {
            improved = false;
            for (int i = 1; i < path.length - 1; i++) {
                for (int j = i + 1; j < path.length; j++) {
                    double interiorForward = 0;
                    double interiorReversed = 0;
                    for (int k = i; k < j; k++) {
                        interiorForward += cost.of(path[k], path[k + 1]);
                        interiorReversed += cost.of(path[k + 1], path[k]);
                    }
                    double before = cost.of(path[i - 1], path[i]) + interiorForward
                        + (j + 1 < path.length ? cost.of(path[j], path[j + 1]) : 0);
                    double after = cost.of(path[i - 1], path[j]) + interiorReversed
                        + (j + 1 < path.length ? cost.of(path[i], path[j + 1]) : 0);
                    if (after < before - 0.01) {
                        for (int lo = i, hi = j; lo < hi; lo++, hi--) {
                            int tmp = path[lo]; path[lo] = path[hi]; path[hi] = tmp;
                        }
                        improved = true;
                    }
                }
            }
        }

        Map<Integer, CandidateStop> byIndex = new HashMap<>();
        for (CandidateStop h : route) byIndex.put(h.index(), h);
        List<CandidateStop> result = new ArrayList<>(route.size());
        for (int k = 1; k < path.length; k++) result.add(byIndex.get(path[k]));
        return result;
    }

    static double routeCost(int originIdx, List<CandidateStop> stops, int destIdx, Cost cost) {
        double total = 0;
        int prev = originIdx;
        for (CandidateStop s : stops) {
            total += cost.of(prev, s.index());
            prev = s.index();
        }
        total += cost.of(prev, destIdx);
        return total;
    }

    /**
     * Phase-4 test helper kept for {@code RouteOptimizationServiceTwoOptTest} — adapts the
     * matrix-based signature to the cost-based one used internally.
     */
    public static List<CandidateStop> twoOpt(List<CandidateStop> route, int originIdx,
                                             RouteCostMatrix matrix) {
        return twoOpt(route, originIdx, matrix::km);
    }
}
