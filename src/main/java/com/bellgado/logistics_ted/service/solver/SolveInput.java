package com.bellgado.logistics_ted.service.solver;

import com.bellgado.logistics_ted.service.distance.RouteCostMatrix;
import java.util.List;
import java.util.Map;

/**
 * Input to a {@link RouteSolver} run. The solver picks ordering using
 * {@code objective.toCost(matrix)} — phase 7 made the objective explicit so callers can request
 * shortest-distance, fastest-time, or a balanced blend in successive calls.
 */
public record SolveInput(
    int originIdx,
    int destIdx,
    List<CandidateStop> candidates,
    Map<Integer, Double> needed,
    RouteCostMatrix matrix,
    ObjectiveSpec objective
) {

    public Cost cost() {
        return objective.toCost(matrix);
    }
}
