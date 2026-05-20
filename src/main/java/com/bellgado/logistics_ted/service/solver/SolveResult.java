package com.bellgado.logistics_ted.service.solver;

import java.util.List;

/**
 * Ordered visit list returned by a {@link RouteSolver}. The list is also the selected set —
 * allocation downstream sorts providers per material by distance regardless of visit order,
 * so a separate "selected" collection would be redundant.
 */
public record SolveResult(List<CandidateStop> orderedStops) {

    public static SolveResult empty() {
        return new SolveResult(List.of());
    }
}
