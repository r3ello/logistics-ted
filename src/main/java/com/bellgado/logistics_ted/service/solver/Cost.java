package com.bellgado.logistics_ted.service.solver;

/**
 * Abstract edge cost the solver uses for ranking. Built by an {@link ObjectiveSpec} from a
 * {@code RouteCostMatrix}; the solver never sees km or minutes directly, only "cost".
 */
@FunctionalInterface
public interface Cost {

    double of(int from, int to);
}
