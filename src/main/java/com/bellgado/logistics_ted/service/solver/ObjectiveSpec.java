package com.bellgado.logistics_ted.service.solver;

import com.bellgado.logistics_ted.service.distance.RouteCostMatrix;

/**
 * What the solver is optimising for, and how much each input weighs. {@link #toCost} turns the
 * spec into a {@link Cost} function bound to a specific matrix.
 *
 *  - {@link Type#SHORTEST_DISTANCE} → minimise kilometres.
 *  - {@link Type#FASTEST_TIME} → minimise minutes.
 *  - {@link Type#BALANCED} → minimise {@code alpha * km + beta * minutes}.
 */
public record ObjectiveSpec(Type type, double alpha, double beta) {

    public enum Type { SHORTEST_DISTANCE, FASTEST_TIME, BALANCED, HOUSES_WAREHOUSES_ONLY, HOUSES_SUPPLIERS_ONLY }

    public static ObjectiveSpec shortestDistance() {
        return new ObjectiveSpec(Type.SHORTEST_DISTANCE, 1.0, 0.0);
    }

    public static ObjectiveSpec fastestTime() {
        return new ObjectiveSpec(Type.FASTEST_TIME, 0.0, 1.0);
    }

    public static ObjectiveSpec balanced(double alpha, double beta) {
        return new ObjectiveSpec(Type.BALANCED, alpha, beta);
    }

    public static ObjectiveSpec housesAndWarehousesOnly() {
        return new ObjectiveSpec(Type.HOUSES_WAREHOUSES_ONLY, 1.0, 0.0);
    }

    public static ObjectiveSpec housesAndSuppliersOnly() {
        return new ObjectiveSpec(Type.HOUSES_SUPPLIERS_ONLY, 1.0, 0.0);
    }

    public String label() {
        return switch (type) {
            case SHORTEST_DISTANCE -> "shortest_distance";
            case FASTEST_TIME -> "fastest_time";
            case BALANCED -> "balanced";
            case HOUSES_WAREHOUSES_ONLY -> "houses_warehouses_only";
            case HOUSES_SUPPLIERS_ONLY -> "houses_suppliers_only";
        };
    }

    public boolean useDepots() {
        return type != Type.HOUSES_SUPPLIERS_ONLY;
    }

    public boolean useSuppliers() {
        return type != Type.HOUSES_WAREHOUSES_ONLY;
    }

    public Cost toCost(RouteCostMatrix matrix) {
        return switch (type) {
            case SHORTEST_DISTANCE, HOUSES_WAREHOUSES_ONLY, HOUSES_SUPPLIERS_ONLY -> matrix::km;
            case FASTEST_TIME -> (a, b) -> matrix.seconds(a, b) / 60.0;
            case BALANCED -> (a, b) -> alpha * matrix.km(a, b) + beta * (matrix.seconds(a, b) / 60.0);
        };
    }
}
