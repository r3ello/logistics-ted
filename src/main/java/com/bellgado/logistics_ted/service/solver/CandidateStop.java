package com.bellgado.logistics_ted.service.solver;

import java.util.HashMap;
import java.util.Map;

/**
 * A house considered as a possible pickup stop. Carries enough information for both the
 * {@link RouteSolver} (id, lat/lng, matrix index, per-material quantities) and the allocation
 * step in {@code RouteOptimizationService} (material name/unit metadata via {@link InventoryEntry}).
 *
 * The {@code index} field is the matrix index assigned per request; mutability is intentional
 * since the same instance is built once and consumed by both the solver and the allocation
 * loop.
 */
public final class CandidateStop {

    private final int id;
    private final String name;
    private final String location;
    private final double lat;
    private final double lng;
    private final Map<Integer, InventoryEntry> inventory = new HashMap<>();
    private int index = -1;

    public CandidateStop(int id, String name, String location, double lat, double lng) {
        this.id = id;
        this.name = name;
        this.location = location;
        this.lat = lat;
        this.lng = lng;
    }

    public int id() { return id; }
    public String name() { return name; }
    public String location() { return location; }
    public double lat() { return lat; }
    public double lng() { return lng; }
    public int index() { return index; }
    public void setIndex(int index) { this.index = index; }
    public Map<Integer, InventoryEntry> inventory() { return inventory; }

    public double qtyOf(int materialId) {
        InventoryEntry e = inventory.get(materialId);
        return e == null ? 0 : e.quantity();
    }
}
