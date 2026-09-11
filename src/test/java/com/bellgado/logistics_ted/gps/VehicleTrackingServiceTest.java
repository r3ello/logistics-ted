package com.bellgado.logistics_ted.gps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bellgado.logistics_ted.domain.Vehicle;
import com.bellgado.logistics_ted.domain.VehiclePosition;
import com.bellgado.logistics_ted.domain.VehicleRouteSegment;
import com.bellgado.logistics_ted.domain.Worker;
import com.bellgado.logistics_ted.repository.VehiclePositionRepository;
import com.bellgado.logistics_ted.repository.VehicleRepository;
import com.bellgado.logistics_ted.repository.VehicleRouteSegmentRepository;
import com.bellgado.logistics_ted.repository.WorkerRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/** What a poll does to the database: discovery, breadcrumbs, window replacement, our fields left alone. */
class VehicleTrackingServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-11T09:25:00Z");
    private static final Instant FIX = Instant.parse("2026-09-11T09:20:25Z");
    private static final Instant FROM = Instant.parse("2026-09-10T21:00:00Z");
    private static final Instant TO = Instant.parse("2026-09-11T07:00:00Z");

    private VehicleRepository vehicles;
    private VehiclePositionRepository positions;
    private VehicleRouteSegmentRepository segments;
    private WorkerRepository workers;
    private VehicleTrackingService service;

    @BeforeEach
    void setUp() {
        vehicles = mock(VehicleRepository.class);
        positions = mock(VehiclePositionRepository.class);
        segments = mock(VehicleRouteSegmentRepository.class);
        workers = mock(WorkerRepository.class);
        service = new VehicleTrackingService(vehicles, positions, segments, workers);
        when(vehicles.save(any(Vehicle.class))).thenAnswer(inv -> {
            Vehicle v = inv.getArgument(0);
            if (v.getId() == null) v.setId(7);
            return v;
        });
    }

    @Test
    void anUnknownCodeBecomesAVehicleWithItsFirstBreadcrumb() {
        when(vehicles.findAll()).thenReturn(List.of());

        int added = service.applyStatus(List.of(status("92195", FIX, 42.71, 23.31)), NOW);

        assertEquals(1, added);
        ArgumentCaptor<Vehicle> saved = ArgumentCaptor.forClass(Vehicle.class);
        verify(vehicles).save(saved.capture());
        Vehicle v = saved.getValue();
        assertEquals("92195", v.getGpsCode());
        assertEquals("МАСТЪР БОБИ", v.getProviderName());
        assertEquals(FIX, v.getLastTs());
        assertEquals(NOW, v.getStatusSyncedAt());
        assertTrue(v.isActive());
        ArgumentCaptor<VehiclePosition> point = ArgumentCaptor.forClass(VehiclePosition.class);
        verify(positions).save(point.capture());
        assertEquals(FIX, point.getValue().getTs());
        assertEquals(42.71, point.getValue().getLat());
    }

    @Test
    void theSameFixSeenTwiceAddsNoSecondPoint() {
        Vehicle existing = vehicle(7, "92195");
        existing.setLastTs(FIX);
        when(vehicles.findAll()).thenReturn(List.of(existing));

        assertEquals(0, service.applyStatus(List.of(status("92195", FIX, 42.71, 23.31)), NOW));

        verify(positions, never()).save(any());
    }

    @Test
    void aSyncNeverTouchesOurOwnFields() {
        Worker driver = mock(Worker.class);
        Vehicle existing = vehicle(7, "92195");
        existing.setAlias("Бобо");
        existing.setNotes("Сервиз на 10.10");
        existing.setActive(false);
        existing.setDriverWorker(driver);
        when(vehicles.findAll()).thenReturn(List.of(existing));

        service.applyStatus(List.of(status("92195", FIX, 42.71, 23.31)), NOW);

        assertEquals("Бобо", existing.getAlias());
        assertEquals("Сервиз на 10.10", existing.getNotes());
        assertFalse(existing.isActive());
        assertSame(driver, existing.getDriverWorker());
        assertEquals("МАСТЪР БОБИ", existing.getProviderName());   // the provider's own fields do update
    }

    @Test
    void aPollWithoutAFixKeepsTheLastKnownPosition() {
        Vehicle existing = vehicle(7, "92195");
        existing.setLastTs(FIX);
        existing.setLastLat(42.71);
        existing.setLastLng(23.31);
        when(vehicles.findAll()).thenReturn(List.of(existing));

        service.applyStatus(List.of(status("92195", null, null, null)), NOW);

        assertEquals(FIX, existing.getLastTs());
        assertEquals(42.71, existing.getLastLat());
        verify(positions, never()).save(any());
    }

    @Test
    void fetchedTripsReplaceTheWholeWindow() {
        Vehicle v = vehicle(7, "49138");
        when(vehicles.findByGpsCode("49138")).thenReturn(Optional.of(v));
        when(segments.findOne(anyInt(), any(), anyBoolean())).thenReturn(Optional.empty());

        int stored = service.applyRoutes("49138", FROM, TO,
            List.of(segment(true, FROM.plusSeconds(3600)), segment(false, FROM.plusSeconds(3900))), NOW);

        assertEquals(2, stored);
        InOrder order = inOrder(segments);
        order.verify(segments).deleteWindow(7, FROM, TO);
        order.verify(segments, times(2)).save(any(VehicleRouteSegment.class));
        assertEquals(NOW, v.getRoutesSyncedAt());
    }

    @Test
    void tripsForAnUnknownVehicleAreDropped() {
        when(vehicles.findByGpsCode("nope")).thenReturn(Optional.empty());

        assertEquals(0, service.applyRoutes("nope", FROM, TO, List.of(segment(true, FROM)), NOW));

        verify(segments, never()).deleteWindow(anyInt(), any(), any());
    }

    @Test
    void assigningAnUnknownWorkerIsRejected() {
        when(vehicles.findById(7)).thenReturn(Optional.of(vehicle(7, "A")));
        when(workers.findById(99)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.update(7, "Бобо", null, true, 99));
    }

    private static Vehicle vehicle(int id, String code) {
        Vehicle v = new Vehicle();
        v.setId(id);
        v.setGpsCode(code);
        return v;
    }

    private static VehicleStatus status(String code, Instant ts, Double lat, Double lng) {
        return new VehicleStatus(code, "МАСТЪР БОБИ", "СВ0000АА", null, true, true, ts, lat, lng,
            29.2, 151, null, 93634.2, 4.12, 12.73, Map.of("Code", code));
    }

    private static RouteSegment segment(boolean moving, Instant start) {
        return new RouteSegment(moving, start, start.plusSeconds(300), 300, 42.61, 23.41, 42.62, 23.40,
            moving ? 1.8 : 0.0, 19.7, 49.3, 3.15, 2.44, 85, 0.14, null, null, null,
            Map.of("Ign", moving ? "1" : "0"));
    }
}
