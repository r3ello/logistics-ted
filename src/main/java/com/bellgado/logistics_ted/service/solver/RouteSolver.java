package com.bellgado.logistics_ted.service.solver;

/**
 * Picks which candidate houses to visit and in which order. Allocation, supplier fallback, and
 * response assembly are downstream concerns and live in {@code RouteOptimizationService}.
 *
 * Phase-6 entry seam: the only implementation today is {@link HeuristicRouteSolver} (exhaustive
 * subset search for N≤15, greedy fallback for N&gt;15, NN + asymmetric-safe 2-opt). A future
 * Timefold-based solver can drop in here without touching the consumer.
 */
public interface RouteSolver {

    SolveResult solve(SolveInput input);
}
