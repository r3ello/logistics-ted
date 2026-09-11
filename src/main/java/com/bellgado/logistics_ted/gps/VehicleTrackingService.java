package com.bellgado.logistics_ted.gps;

import com.bellgado.logistics_ted.domain.Vehicle;
import com.bellgado.logistics_ted.domain.VehiclePosition;
import com.bellgado.logistics_ted.domain.VehicleRouteSegment;
import com.bellgado.logistics_ted.domain.Worker;
import com.bellgado.logistics_ted.repository.VehiclePositionRepository;
import com.bellgado.logistics_ted.repository.VehicleRepository;
import com.bellgado.logistics_ted.repository.VehicleRouteSegmentRepository;
import com.bellgado.logistics_ted.repository.WorkerRepository;
import com.bellgado.logistics_ted.web.dto.VehicleDto;
import com.bellgado.logistics_ted.web.dto.VehiclePositionDto;
import com.bellgado.logistics_ted.web.dto.VehicleTripsDto;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence side of the GPS integration: {@link GpsPoller} writes through here and the REST API
 * reads through here. Nothing in this class talks to GPS.bg.
 *
 * <p>{@code @Component}, not {@code @Service}, like the rest of the {@code gps} package: the package
 * stays out of {@code ServiceLoggingAspect}'s argument logging as a rule.
 */
@Component
public class VehicleTrackingService {

    private final VehicleRepository vehicles;
    private final VehiclePositionRepository positions;
    private final VehicleRouteSegmentRepository segments;
    private final WorkerRepository workers;

    public VehicleTrackingService(VehicleRepository vehicles, VehiclePositionRepository positions,
                                  VehicleRouteSegmentRepository segments, WorkerRepository workers) {
        this.vehicles = vehicles;
        this.positions = positions;
        this.segments = segments;
        this.workers = workers;
    }

    // ── writes (poller) ─────────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<String> activeCodes() {
        return vehicles.findActiveCodes();
    }

    /**
     * Upserts every vehicle GetStatus reported — unknown codes become new vehicles — and stores a
     * breadcrumb point for each new fix. Our own fields (alias, notes, active, driver) are untouched.
     * Returns the number of new breadcrumb points.
     */
    @Transactional
    public int applyStatus(List<VehicleStatus> statuses, Instant syncedAt) {
        Map<String, Vehicle> byCode = new HashMap<>();
        vehicles.findAll().forEach(v -> byCode.put(v.getGpsCode(), v));
        int added = 0;
        for (VehicleStatus s : statuses) {
            if (s.code() == null) continue;
            Vehicle v = byCode.get(s.code());
            if (v == null) {
                v = new Vehicle();
                v.setGpsCode(s.code());
                v.setCreatedAt(syncedAt);
                byCode.put(s.code(), v);
            }
            boolean newFix = s.lastTs() != null && !s.lastTs().equals(v.getLastTs());
            v.setProviderName(s.name());
            v.setRegNumber(s.regNumber());
            v.setIgnition(s.ignition());
            v.setOnline(s.online());
            v.setLastDriver(s.driver());
            v.setLastAddress(s.address());
            v.setOdometerKm(s.odometerKm());
            v.setBatteryV(s.batteryV());
            v.setExtPowerV(s.extPowerV());
            if (s.lastTs() != null) {
                // Keep the previous fix when a poll comes back without one.
                v.setLastTs(s.lastTs());
                v.setLastLat(s.lat());
                v.setLastLng(s.lng());
                v.setLastSpeedKmh(s.speedKmh());
                v.setLastHeading(s.heading());
            }
            v.setLastStatusJson(new LinkedHashMap<String, Object>(s.raw()));
            v.setStatusSyncedAt(syncedAt);
            v = vehicles.save(v);

            if (newFix && s.lat() != null && s.lng() != null
                    && !positions.existsByVehicleIdAndTs(v.getId(), s.lastTs())) {
                VehiclePosition p = new VehiclePosition();
                p.setVehicle(v);
                p.setTs(s.lastTs());
                p.setLat(s.lat());
                p.setLng(s.lng());
                p.setSpeedKmh(s.speedKmh());
                p.setHeading(s.heading());
                p.setIgnition(s.ignition());
                positions.save(p);
                added++;
            }
        }
        return added;
    }

    /**
     * Replaces one vehicle's segments starting in [from, to] with what GPS.bg just returned. A replace,
     * not a merge: an open drive fetched at noon can come back re-split an hour later, and merging would
     * leave the stale half behind. Segments that began before {@code from} are updated in place.
     */
    @Transactional
    public int applyRoutes(String vehicleCode, Instant from, Instant to, List<RouteSegment> fetched, Instant syncedAt) {
        Optional<Vehicle> found = vehicles.findByGpsCode(vehicleCode);
        if (found.isEmpty()) return 0;
        Vehicle v = found.get();
        segments.deleteWindow(v.getId(), from, to);
        int stored = 0;
        for (RouteSegment s : fetched) {
            if (s.start() == null) continue;
            VehicleRouteSegment row = segments.findOne(v.getId(), s.start(), s.moving())
                .orElseGet(VehicleRouteSegment::new);
            row.setVehicle(v);
            row.setMoving(s.moving());
            row.setStartTs(s.start());
            row.setEndTs(s.end());
            row.setDurationS(s.durationS());
            row.setStartLat(s.startLat());
            row.setStartLng(s.startLng());
            row.setEndLat(s.endLat());
            row.setEndLng(s.endLng());
            row.setDistanceKm(s.distanceKm());
            row.setAvgSpeedKmh(s.avgSpeedKmh());
            row.setMaxSpeedKmh(s.maxSpeedKmh());
            row.setMaxAccel(s.maxAccel());
            row.setMaxDecel(s.maxDecel());
            row.setIdleS(s.idleS());
            row.setFuelEstL(s.fuelEstL());
            row.setDriver(s.driver());
            row.setStartPlace(s.startPlace());
            row.setEndPlace(s.endPlace());
            row.setRawJson(new LinkedHashMap<String, Object>(s.raw()));
            row.setSyncedAt(syncedAt);
            segments.save(row);
            stored++;
        }
        v.setRoutesSyncedAt(syncedAt);
        return stored;
    }

    // ── reads (REST API) ────────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<VehicleDto> list() {
        return vehicles.findAllWithDriver().stream()
            .map(v -> toDto(v, false))
            .sorted(Comparator.comparing(VehicleDto::active).reversed()
                .thenComparing(VehicleDto::name, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
            .toList();
    }

    @Transactional(readOnly = true)
    public Optional<VehicleDto> get(Integer id) {
        return vehicles.findById(id).map(v -> toDto(v, true));
    }

    @Transactional(readOnly = true)
    public Optional<String> gpsCodeOf(Integer id) {
        return vehicles.findById(id).map(Vehicle::getGpsCode);
    }

    /** Our own fields only; the provider's are overwritten by the next poll anyway. */
    @Transactional
    public Optional<VehicleDto> update(Integer id, String alias, String notes, boolean active, Integer driverWorkerId) {
        Optional<Vehicle> found = vehicles.findById(id);
        if (found.isEmpty()) return Optional.empty();
        Worker driver = null;
        if (driverWorkerId != null) {
            driver = workers.findById(driverWorkerId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown worker " + driverWorkerId + "."));
        }
        Vehicle v = found.get();
        v.setAlias(blankToNull(alias));
        v.setNotes(blankToNull(notes));
        v.setActive(active);
        v.setDriverWorker(driver);
        return Optional.of(toDto(v, true));
    }

    @Transactional(readOnly = true)
    public Optional<List<VehiclePositionDto>> positions(Integer id, LocalDate day, ZoneId zone) {
        if (!vehicles.existsById(id)) return Optional.empty();
        Instant from = day.atStartOfDay(zone).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(zone).toInstant();
        return Optional.of(positions.findWindow(id, from, to).stream()
            .map(p -> new VehiclePositionDto(p.getTs(), p.getLat(), p.getLng(), p.getSpeedKmh(),
                p.getHeading(), p.getIgnition()))
            .toList());
    }

    @Transactional(readOnly = true)
    public Optional<VehicleTripsDto> trips(Integer id, LocalDate day, ZoneId zone) {
        return vehicles.findById(id).map(v -> {
            Instant from = day.atStartOfDay(zone).toInstant();
            Instant to = day.plusDays(1).atStartOfDay(zone).toInstant();
            List<VehicleTripsDto.Segment> rows = segments.findWindow(id, from, to).stream()
                .map(VehicleTrackingService::toSegment)
                .toList();
            return new VehicleTripsDto(id, day, v.getRoutesSyncedAt(), VehicleTripsDto.Summary.of(rows), rows);
        });
    }

    private static VehicleDto toDto(Vehicle v, boolean withTelemetry) {
        Worker d = v.getDriverWorker();
        String name = v.getAlias() != null ? v.getAlias()
            : v.getProviderName() != null ? v.getProviderName() : v.getGpsCode();
        return new VehicleDto(
            v.getId(), v.getGpsCode(), name, v.getProviderName(), v.getAlias(), v.getRegNumber(),
            v.getNotes(), v.isActive(),
            d != null ? d.getId() : null, d != null ? d.getName() : null,
            v.getLastTs(), v.getLastLat(), v.getLastLng(), v.getLastSpeedKmh(), v.getLastHeading(),
            v.getIgnition(), v.getOnline(), v.getLastDriver(), v.getLastAddress(),
            v.getOdometerKm(), v.getBatteryV(), v.getExtPowerV(),
            v.getStatusSyncedAt(), v.getRoutesSyncedAt(),
            withTelemetry ? v.getLastStatusJson() : null);
    }

    private static VehicleTripsDto.Segment toSegment(VehicleRouteSegment s) {
        return new VehicleTripsDto.Segment(
            s.isMoving(), s.getStartTs(), s.getEndTs(), s.getDurationS(),
            s.getStartLat(), s.getStartLng(), s.getEndLat(), s.getEndLng(),
            s.getDistanceKm(), s.getAvgSpeedKmh(), s.getMaxSpeedKmh(), s.getMaxAccel(), s.getMaxDecel(),
            s.getIdleS(), s.getFuelEstL(), s.getDriver(), s.getStartPlace(), s.getEndPlace());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
