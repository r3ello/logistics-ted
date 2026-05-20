package com.bellgado.logistics_ted.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bellgado.logistics_ted.domain.House;
import com.bellgado.logistics_ted.domain.Inventory;
import com.bellgado.logistics_ted.domain.Material;
import com.bellgado.logistics_ted.domain.Supplier;
import com.bellgado.logistics_ted.domain.SupplierInventory;
import com.bellgado.logistics_ted.domain.Warehouse;
import com.bellgado.logistics_ted.repository.HouseRepository;
import com.bellgado.logistics_ted.repository.InventoryRepository;
import com.bellgado.logistics_ted.repository.MaterialRepository;
import com.bellgado.logistics_ted.repository.SupplierInventoryRepository;
import com.bellgado.logistics_ted.config.RoutingProperties;
import com.bellgado.logistics_ted.service.RouteOptimizationService.OrderValidationException;
import com.bellgado.logistics_ted.service.distance.HaversineDistanceService;
import com.bellgado.logistics_ted.service.distance.HaversineMatrixService;
import com.bellgado.logistics_ted.service.solver.HeuristicRouteSolver;
import com.bellgado.logistics_ted.web.dto.OrderRequest;
import com.bellgado.logistics_ted.web.dto.OrderResponse;
import com.bellgado.logistics_ted.web.dto.OrderResponse.RouteStopDto;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Phase-0 golden tests for {@link RouteOptimizationService}. Pin every observable in the
 * /api/calculate-order response — route order, totalDistance, mapsUrl, contributions, deficits —
 * so phases 1–3 (extract DistanceService, RouteMatrixService, caching) can refactor internals
 * without behavior drift. Phase 4 will swap haversine for Google Routes and will intentionally
 * break the distance/mapsUrl numbers; at that point a parallel road-distance set is added.
 *
 * Pure JUnit + Mockito — no Spring context, no Postgres. Safe to run via
 * {@code mvnw -Dtest=RouteOptimizationServiceTest test} when the DB is unreachable.
 */
class RouteOptimizationServiceTest {

    private static final int DEST_ID = 99;

    private HouseRepository houses;
    private InventoryRepository inventories;
    private MaterialRepository materials;
    private SupplierInventoryRepository supplierInventory;
    private RouteOptimizationService service;

    @BeforeEach
    void setUp() {
        houses = mock(HouseRepository.class);
        inventories = mock(InventoryRepository.class);
        materials = mock(MaterialRepository.class);
        supplierInventory = mock(SupplierInventoryRepository.class);
        when(supplierInventory.findStocked(any())).thenReturn(List.of());
        var matrix = new HaversineMatrixService(new HaversineDistanceService());
        var supplierFallback = new SupplierFallbackService(supplierInventory, matrix);
        var props = new RoutingProperties("haversine",
            new RoutingProperties.Google("", "https://example.com", "DRIVE", "TRAFFIC_AWARE", 5),
            new RoutingProperties.Haversine(40),
            new RoutingProperties.Balanced(1.0, 1.0),
            new RoutingProperties.Cache(86400, 50000));
        service = new RouteOptimizationService(houses, inventories, materials,
            new ServerMessages(), matrix, supplierFallback, new HeuristicRouteSolver(), props);
    }

    // ── golden behavior ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("exhaustive: picks the single closest house that covers demand on its own")
    void singleHouseExactMatch() {
        Material m1 = material(1, "Brick", "pcs");
        House dest = house(DEST_ID, "Site D", "addr-d", 1.0, 1.0);
        House a    = house(1, "House A", "addr-a", 10.0, 10.0);
        House b    = house(2, "House B", "addr-b", 0.5, 0.5);

        mockRepos(dest, List.of(m1), List.of(
            inventory(a, m1, 20.0),
            inventory(b, m1, 20.0)
        ));

        OrderResponse r = service.calculate(new OrderRequest(0.0, 0.0, "Origin", DEST_ID,
            Map.<String, Object>of("1", "10"), "en"));

        assertThat(r.totalStops()).isEqualTo(1);
        assertThat(r.fullyFulfilled()).isTrue();
        assertThat(r.deficit()).isEmpty();
        assertThat(r.route()).extracting(RouteStopDto::id).containsExactly(2);
        assertThat(r.mapsUrl())
            .isEqualTo("https://www.google.com/maps/dir/0.0,0.0/0.5,0.5/1.0,1.0");
        assertThat(r.totalDistance())
            .isEqualTo(Math.round(hv(0, 0, 0.5, 0.5) + hv(0.5, 0.5, 1.0, 1.0)));

        var contribution = r.route().get(0).contribution();
        assertThat(contribution).containsOnlyKeys(1);
        var c1 = contribution.get(1);
        assertThat(c1.quantity()).isEqualTo(10.0);
        assertThat(c1.selectionReason()).isEqualTo("optimal_single");
        assertThat(c1.availableQty()).isEqualTo(20.0);
        assertThat(c1.name()).isEqualTo("Brick");
        assertThat(c1.unit()).isEqualTo("pcs");
        assertThat(c1.distanceFromOrigin())
            .isEqualTo((int) Math.round(hv(0, 0, 0.5, 0.5)));
    }

    @Test
    @DisplayName("exhaustive: two-house subset beats three-house subset with a far detour")
    void multiHouseCoverage() {
        Material m1 = material(1, "Brick", "pcs");
        Material m2 = material(2, "Cement", "kg");
        House dest = house(DEST_ID, "Dest", "addr-d", 0.3, 0.3);
        House a    = house(1, "A", "addr-a", 0.1, 0.1);
        House b    = house(2, "B", "addr-b", 0.2, 0.2);
        House c    = house(3, "C", "addr-c", 5.0, 5.0);

        mockRepos(dest, List.of(m1, m2), List.of(
            inventory(a, m1, 10.0),
            inventory(b, m1, 5.0),
            inventory(b, m2, 10.0),
            inventory(c, m2, 5.0)
        ));

        OrderResponse r = service.calculate(new OrderRequest(0.0, 0.0, "Origin", DEST_ID,
            Map.<String, Object>of("1", "15", "2", "5"), "en"));

        assertThat(r.totalStops()).isEqualTo(2);
        assertThat(r.route()).extracting(RouteStopDto::id).containsExactly(1, 2);
        assertThat(r.deficit()).isEmpty();
        assertThat(r.fullyFulfilled()).isTrue();
        assertThat(r.mapsUrl())
            .isEqualTo("https://www.google.com/maps/dir/0.0,0.0/0.1,0.1/0.2,0.2/0.3,0.3");
        assertThat(r.totalDistance()).isEqualTo(Math.round(
            hv(0, 0, 0.1, 0.1) + hv(0.1, 0.1, 0.2, 0.2) + hv(0.2, 0.2, 0.3, 0.3)));

        var aContrib = r.route().get(0).contribution();
        assertThat(aContrib).containsOnlyKeys(1);
        assertThat(aContrib.get(1).quantity()).isEqualTo(10.0);
        assertThat(aContrib.get(1).selectionReason()).isEqualTo("optimal_multi");
        assertThat(aContrib.get(1).availableQty()).isEqualTo(10.0);

        var bContrib = r.route().get(1).contribution();
        assertThat(bContrib).containsOnlyKeys(1, 2);
        assertThat(bContrib.get(1).quantity()).isEqualTo(5.0);
        assertThat(bContrib.get(1).availableQty()).isEqualTo(5.0);
        assertThat(bContrib.get(2).quantity()).isEqualTo(5.0);
        assertThat(bContrib.get(2).availableQty()).isEqualTo(10.0);
    }

    @Test
    @DisplayName("exhaustive: when no subset covers demand, zero stops and full deficit are reported")
    void deficitWhenNoSubsetCoversDemand() {
        Material m1 = material(1, "Brick", "pcs");
        House dest = house(DEST_ID, "Dest", "addr-d", 0.3, 0.3);
        House a    = house(1, "A", "addr-a", 0.1, 0.1);
        House b    = house(2, "B", "addr-b", 0.2, 0.2);

        mockRepos(dest, List.of(m1), List.of(
            inventory(a, m1, 5.0),
            inventory(b, m1, 3.0)
        ));

        OrderResponse r = service.calculate(new OrderRequest(0.0, 0.0, "Origin", DEST_ID,
            Map.<String, Object>of("1", "100"), "en"));

        assertThat(r.totalStops()).isEqualTo(0);
        assertThat(r.route()).isEmpty();
        assertThat(r.fullyFulfilled()).isFalse();
        assertThat(r.deficit()).hasSize(1);
        assertThat(r.deficit().get(0).quantity()).isEqualTo(100.0);
        assertThat(r.deficit().get(0).name()).isEqualTo("Brick");
        assertThat(r.deficit().get(0).unit()).isEqualTo("pcs");
        assertThat(r.mapsUrl())
            .isEqualTo("https://www.google.com/maps/dir/0.0,0.0/0.3,0.3");
        assertThat(r.totalDistance()).isEqualTo(Math.round(hv(0, 0, 0.3, 0.3)));
    }

    @Test
    @DisplayName("greedy fallback (n > 15): picks closest houses in order until demand is met")
    void greedyFallbackWithManyCandidates() {
        Material m1 = material(1, "Brick", "pcs");
        House dest = house(DEST_ID, "Dest", "addr-d", 1.0, 1.0);

        List<Inventory> invs = new ArrayList<>();
        for (int i = 1; i <= 16; i++) {
            House h = house(i, "H" + i, "addr-" + i, 0.01 * i, 0.01 * i);
            invs.add(inventory(h, m1, 10.0));
        }
        mockRepos(dest, List.of(m1), invs);

        OrderResponse r = service.calculate(new OrderRequest(0.0, 0.0, "Origin", DEST_ID,
            Map.<String, Object>of("1", "50"), "en"));

        assertThat(r.totalStops()).isEqualTo(5);
        assertThat(r.route()).extracting(RouteStopDto::id).containsExactly(1, 2, 3, 4, 5);
        assertThat(r.deficit()).isEmpty();
        assertThat(r.fullyFulfilled()).isTrue();

        for (int i = 0; i < 5; i++) {
            var stop = r.route().get(i);
            assertThat(stop.contribution()).containsOnlyKeys(1);
            assertThat(stop.contribution().get(1).quantity()).isEqualTo(10.0);
            assertThat(stop.contribution().get(1).selectionReason()).isEqualTo("optimal_multi");
        }

        double expected = hv(0, 0, 0.01, 0.01)
            + hv(0.01, 0.01, 0.02, 0.02)
            + hv(0.02, 0.02, 0.03, 0.03)
            + hv(0.03, 0.03, 0.04, 0.04)
            + hv(0.04, 0.04, 0.05, 0.05)
            + hv(0.05, 0.05, 1.0, 1.0);
        assertThat(r.totalDistance()).isEqualTo(Math.round(expected));
        assertThat(r.mapsUrl())
            .isEqualTo("https://www.google.com/maps/dir/"
                + "0.0,0.0/0.01,0.01/0.02,0.02/0.03,0.03/0.04,0.04/0.05,0.05/1.0,1.0");
    }

    @Test
    @DisplayName("alternatives: response carries up to 3 deduplicated objective options")
    void responseExposesObjectiveAlternatives() {
        Material m1 = material(1, "Brick", "pcs");
        House dest = house(DEST_ID, "Site D", "addr-d", 1.0, 1.0);
        House b    = house(2, "House B", "addr-b", 0.5, 0.5);

        mockRepos(dest, List.of(m1), List.of(inventory(b, m1, 20.0)));

        OrderResponse r = service.calculate(new OrderRequest(0.0, 0.0, "Origin", DEST_ID,
            Map.<String, Object>of("1", "10"), "en"));

        // Top-level fields mirror the first alternative (always shortest_distance).
        assertThat(r.alternatives()).isNotEmpty();
        assertThat(r.alternatives().get(0).objective()).isEqualTo("shortest_distance");
        assertThat(r.alternatives().get(0).totalDistance()).isEqualTo(r.totalDistance());
        assertThat(r.alternatives().get(0).totalMinutes()).isEqualTo(r.totalMinutes());

        // With a single candidate and symmetric haversine, all three objectives pick the same
        // route, so the alternatives list dedupes to one entry.
        assertThat(r.alternatives()).hasSize(1);

        // Haversine-mode time estimate: 40 km/h, so ~157 km ≈ 235 minutes.
        assertThat(r.totalMinutes()).isPositive();
    }

    @Test
    @DisplayName("supplier fallback: covers full demand when no house subset can")
    void supplierFallbackCoversWhenHousesCannot() {
        Material m1 = material(1, "Brick", "pcs");
        House dest = house(DEST_ID, "Dest", "addr-d", 0.3, 0.3);
        House a    = house(1, "A", "addr-a", 0.1, 0.1);

        // House A only has 5 of m1 but the order needs 20 — exhaustive search rejects every
        // subset via coversDemand, so no house is picked and the full 20 falls to the supplier.
        mockRepos(dest, List.of(m1), List.of(inventory(a, m1, 5.0)));

        Supplier s1 = supplier(1, "Sup1", "addr-s1", 0.2, 0.1);
        when(supplierInventory.findStocked(any()))
            .thenReturn(List.of(supplierInv(s1, m1, 30.0)));

        OrderResponse r = service.calculate(new OrderRequest(0.0, 0.0, "Origin", DEST_ID,
            Map.<String, Object>of("1", "20"), "en"));

        assertThat(r.route()).isEmpty();
        assertThat(r.totalStops()).isZero();

        assertThat(r.supplierStops()).hasSize(1);
        var supStop = r.supplierStops().get(0);
        assertThat(supStop.id()).isEqualTo(1);
        assertThat(supStop.name()).isEqualTo("Sup1");
        assertThat(supStop.contribution()).containsOnlyKeys(1);
        assertThat(supStop.contribution().get(1).quantity()).isEqualTo(20.0);
        assertThat(supStop.contribution().get(1).selectionReason()).isEqualTo("supplier_fallback");

        assertThat(r.deficit()).isEmpty();
        assertThat(r.fullyFulfilled()).isTrue();
        assertThat(r.mapsUrl()).isEqualTo(
            "https://www.google.com/maps/dir/0.0,0.0/0.2,0.1/0.3,0.3");

        double anchorToDest = hv(0, 0, 0.3, 0.3);
        double supplierLeg = hv(0, 0, 0.2, 0.1) + hv(0.2, 0.1, 0.3, 0.3);
        double extra = Math.max(0, supplierLeg - anchorToDest);
        assertThat(r.totalDistance()).isEqualTo(Math.round(anchorToDest + extra));
    }

    // ── validation paths ────────────────────────────────────────────────────────

    @Test
    void rejectsMissingStartCoords() {
        assertThatThrownBy(() -> service.calculate(new OrderRequest(null, 1.0, "Origin", DEST_ID,
                Map.<String, Object>of("1", "10"), "en")))
            .isInstanceOf(OrderValidationException.class)
            .hasMessage("Please pick a starting location on the map.");
    }

    @Test
    void rejectsMissingDestinationId() {
        assertThatThrownBy(() -> service.calculate(new OrderRequest(0.0, 0.0, "Origin", null,
                Map.<String, Object>of("1", "10"), "en")))
            .isInstanceOf(OrderValidationException.class)
            .hasMessage("Please select a destination house.");
    }

    @Test
    void rejectsEmptyMaterialsMap() {
        assertThatThrownBy(() -> service.calculate(new OrderRequest(0.0, 0.0, "Origin", DEST_ID,
                Map.<String, Object>of(), "en")))
            .isInstanceOf(OrderValidationException.class)
            .hasMessage("Order has no valid quantities.");
    }

    @Test
    void rejectsMaterialsWithOnlyNonNumericValues() {
        when(houses.findById(DEST_ID))
            .thenReturn(Optional.of(house(DEST_ID, "D", "addr", 1.0, 1.0)));

        assertThatThrownBy(() -> service.calculate(new OrderRequest(0.0, 0.0, "Origin", DEST_ID,
                Map.<String, Object>of("1", "not-a-number"), "en")))
            .isInstanceOf(OrderValidationException.class)
            .hasMessage("Order has no valid quantities.");
    }

    @Test
    void rejectsUnknownDestination() {
        when(houses.findById(DEST_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.calculate(new OrderRequest(0.0, 0.0, "Origin", DEST_ID,
                Map.<String, Object>of("1", "10"), "en")))
            .isInstanceOf(OrderValidationException.class)
            .hasMessage("Destination house not found.");
    }

    @Test
    void rejectsDestinationWithoutCoordinates() {
        House dest = new House();
        dest.setId(DEST_ID);
        dest.setName("D");
        dest.setLocation("addr");
        when(houses.findById(DEST_ID)).thenReturn(Optional.of(dest));

        assertThatThrownBy(() -> service.calculate(new OrderRequest(0.0, 0.0, "Origin", DEST_ID,
                Map.<String, Object>of("1", "10"), "en")))
            .isInstanceOf(OrderValidationException.class)
            .hasMessage("Destination house not found.");
    }

    @Test
    void bulgarianLocaleReturnsLocalizedErrorMessage() {
        assertThatThrownBy(() -> service.calculate(new OrderRequest(null, null, null, DEST_ID,
                Map.<String, Object>of("1", "10"), "bg")))
            .hasMessage("Моля изберете начална локация на картата.");
    }

    @Test
    @DisplayName("haversine matches external reference: Madrid → Barcelona ≈ 504 km")
    void haversineExternalReference() {
        double km = new HaversineDistanceService().km(40.4168, -3.7038, 41.3851, 2.1734);
        assertThat(km).isCloseTo(504.0, Offset.offset(1.5));
    }

    // ── fixture helpers ─────────────────────────────────────────────────────────

    private void mockRepos(House destination, List<Material> mats, List<Inventory> invs) {
        when(houses.findById(destination.getId())).thenReturn(Optional.of(destination));
        when(materials.findAllByOrderByIdAsc()).thenReturn(mats);
        when(inventories.findCandidatesForOrder(destination.getId())).thenReturn(invs);
    }

    private static House house(int id, String name, String location, double lat, double lng) {
        House h = new House();
        h.setId(id);
        h.setName(name);
        h.setLocation(location);
        h.setLat(BigDecimal.valueOf(lat));
        h.setLng(BigDecimal.valueOf(lng));
        return h;
    }

    private static Material material(int id, String name, String unit) {
        Material m = new Material();
        m.setId(id);
        m.setName(name);
        m.setUnit(unit);
        m.setPrice(BigDecimal.ZERO);
        return m;
    }

    private static Supplier supplier(int id, String name, String location, double lat, double lng) {
        Supplier s = new Supplier();
        s.setId(id);
        s.setName(name);
        s.setLocation(location);
        s.setLat(BigDecimal.valueOf(lat));
        s.setLng(BigDecimal.valueOf(lng));
        return s;
    }

    private static SupplierInventory supplierInv(Supplier s, Material m, double quantity) {
        SupplierInventory si = new SupplierInventory();
        si.setSupplier(s);
        si.setMaterial(m);
        si.setQuantity(BigDecimal.valueOf(quantity));
        return si;
    }

    private static Inventory inventory(House h, Material m, double quantity) {
        Warehouse w = new Warehouse();
        w.setHouse(h);
        w.setId(h.getId());
        Inventory inv = new Inventory();
        inv.setWarehouse(w);
        inv.setMaterial(m);
        inv.setQuantity(BigDecimal.valueOf(quantity));
        return inv;
    }

    private static double hv(double lat1, double lng1, double lat2, double lng2) {
        double r = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
            * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
