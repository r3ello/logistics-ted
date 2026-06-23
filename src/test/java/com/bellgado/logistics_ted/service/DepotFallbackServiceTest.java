package com.bellgado.logistics_ted.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bellgado.logistics_ted.domain.Depot;
import com.bellgado.logistics_ted.domain.DepotInventory;
import com.bellgado.logistics_ted.domain.Material;
import com.bellgado.logistics_ted.repository.DepotInventoryRepository;
import com.bellgado.logistics_ted.service.DepotFallbackService.DepotFallbackResult;
import com.bellgado.logistics_ted.service.SupplierFallbackService.RemainingNeed;
import com.bellgado.logistics_ted.service.distance.HaversineDistanceService;
import com.bellgado.logistics_ted.service.distance.HaversineMatrixService;
import com.bellgado.logistics_ted.service.solver.ObjectiveSpec;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Mirrors {@link SupplierFallbackServiceTest} for the tier-2 warehouse (depot) fallback. */
class DepotFallbackServiceTest {

    private DepotInventoryRepository repo;
    private DepotFallbackService service;

    @BeforeEach
    void setUp() {
        repo = mock(DepotInventoryRepository.class);
        service = new DepotFallbackService(repo,
            new HaversineMatrixService(new HaversineDistanceService()));
    }

    @Test
    void emptyNeedsReturnsEmptyAndDoesNotHitRepository() {
        DepotFallbackResult result = service.coverDeficits(
            new LinkedHashMap<>(), 0.0, 0.0, 1.0, 1.0, ObjectiveSpec.shortestDistance());

        assertThat(result.stops()).isEmpty();
        assertThat(result.remaining()).isEmpty();
        assertThat(result.extraKm()).isZero();
        verify(repo, never()).findStocked(any());
    }

    @Test
    void noStockedDepotsLeavesDeficitsUntouched() {
        when(repo.findStocked(any())).thenReturn(List.of());

        DepotFallbackResult result = service.coverDeficits(
            needs(Map.of(1, need(10, "Brick", "pcs"))),
            0.0, 0.0, 1.0, 1.0, ObjectiveSpec.shortestDistance());

        assertThat(result.stops()).isEmpty();
        assertThat(result.remaining()).containsOnlyKeys(1);
        assertThat(result.remaining().get(1).quantity()).isEqualTo(10.0);
        assertThat(result.extraKm()).isZero();
    }

    @Test
    void closestDepotCoversSingleMaterialFully() {
        Material m1 = material(1, "Brick", "pcs");
        Depot near = depot(10, "Near", 0.1, 0.0);
        Depot far  = depot(11, "Far",  1.0, 1.0);

        when(repo.findStocked(any())).thenReturn(List.of(
            depotInv(near, m1, 20),
            depotInv(far,  m1, 20)
        ));

        DepotFallbackResult result = service.coverDeficits(
            needs(Map.of(1, need(5, "Brick", "pcs"))),
            0.0, 0.0, 0.5, 0.0, ObjectiveSpec.shortestDistance());

        assertThat(result.stops()).hasSize(1);
        assertThat(result.stops().get(0).id()).isEqualTo(10);
        assertThat(result.stops().get(0).contribution().get(1).quantity()).isEqualTo(5.0);
        assertThat(result.remaining()).isEmpty();
    }

    @Test
    void depotShortfallPropagatesAsRemainingDeficit() {
        Material m1 = material(1, "Brick", "pcs");
        Depot d = depot(10, "Only", 0.1, 0.0);

        when(repo.findStocked(any())).thenReturn(List.of(depotInv(d, m1, 3)));

        DepotFallbackResult result = service.coverDeficits(
            needs(Map.of(1, need(10, "Brick", "pcs"))),
            0.0, 0.0, 1.0, 1.0, ObjectiveSpec.shortestDistance());

        assertThat(result.stops()).hasSize(1);
        assertThat(result.stops().get(0).contribution().get(1).quantity()).isEqualTo(3.0);
        assertThat(result.remaining()).containsOnlyKeys(1);
        assertThat(result.remaining().get(1).quantity()).isEqualTo(7.0);
    }

    @Test
    void multipleMaterialsFromSameDepotAggregateIntoOneStop() {
        Material m1 = material(1, "Brick", "pcs");
        Material m2 = material(2, "Cement", "kg");
        Depot d = depot(10, "Combo", 0.1, 0.1);

        when(repo.findStocked(any())).thenReturn(List.of(
            depotInv(d, m1, 10),
            depotInv(d, m2, 8)
        ));

        DepotFallbackResult result = service.coverDeficits(
            needs(Map.of(
                1, need(5, "Brick", "pcs"),
                2, need(3, "Cement", "kg"))),
            0.0, 0.0, 1.0, 1.0, ObjectiveSpec.shortestDistance());

        assertThat(result.stops()).hasSize(1);
        var stop = result.stops().get(0);
        assertThat(stop.contribution()).containsOnlyKeys(1, 2);
        assertThat(stop.contribution().get(1).quantity()).isEqualTo(5.0);
        assertThat(stop.contribution().get(2).quantity()).isEqualTo(3.0);
        assertThat(result.remaining()).isEmpty();
    }

    @Test
    void materialWithNoStockingDepotStaysInRemaining() {
        Material m1 = material(1, "Brick", "pcs");
        Depot d = depot(10, "Brick-only", 0.1, 0.0);

        when(repo.findStocked(any())).thenReturn(List.of(depotInv(d, m1, 10)));

        DepotFallbackResult result = service.coverDeficits(
            needs(Map.of(
                1, need(5, "Brick", "pcs"),
                2, need(3, "Cement", "kg"))),
            0.0, 0.0, 1.0, 1.0, ObjectiveSpec.shortestDistance());

        assertThat(result.stops()).hasSize(1);
        assertThat(result.stops().get(0).contribution()).containsOnlyKeys(1);
        assertThat(result.remaining()).containsOnlyKeys(2);
        assertThat(result.remaining().get(2).quantity()).isEqualTo(3.0);
    }

    @Test
    void picksAreOrderedByDistanceFromAnchor() {
        Material m1 = material(1, "Brick", "pcs");
        Material m2 = material(2, "Cement", "kg");
        Depot near = depot(10, "Near", 0.1, 0.0);
        Depot far  = depot(11, "Far",  0.5, 0.0);

        when(repo.findStocked(any())).thenReturn(List.of(
            depotInv(near, m1, 5),
            depotInv(far,  m2, 5)
        ));

        DepotFallbackResult result = service.coverDeficits(
            needs(Map.of(
                1, need(5, "Brick", "pcs"),
                2, need(5, "Cement", "kg"))),
            0.0, 0.0, 1.0, 0.0, ObjectiveSpec.shortestDistance());

        assertThat(result.stops()).extracting(s -> s.id()).containsExactly(10, 11);
    }

    private static Map<Integer, RemainingNeed> needs(Map<Integer, RemainingNeed> seed) {
        return new LinkedHashMap<>(seed);
    }

    private static RemainingNeed need(double qty, String name, String unit) {
        return new RemainingNeed(qty, name, unit);
    }

    private static Material material(int id, String name, String unit) {
        Material m = new Material();
        m.setId(id);
        m.setName(name);
        m.setUnit(unit);
        m.setPrice(BigDecimal.ZERO);
        return m;
    }

    private static Depot depot(int id, String name, double lat, double lng) {
        Depot d = new Depot();
        d.setId(id);
        d.setName(name);
        d.setLocation("loc-" + id);
        d.setLat(BigDecimal.valueOf(lat));
        d.setLng(BigDecimal.valueOf(lng));
        return d;
    }

    private static DepotInventory depotInv(Depot d, Material m, double quantity) {
        DepotInventory di = new DepotInventory();
        di.setDepot(d);
        di.setMaterial(m);
        di.setQuantity(BigDecimal.valueOf(quantity));
        return di;
    }
}
