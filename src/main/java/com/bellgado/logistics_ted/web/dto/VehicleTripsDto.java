package com.bellgado.logistics_ted.web.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** One vehicle's driving and stopped segments for one day, with day totals. */
public record VehicleTripsDto(
    Integer vehicleId,
    LocalDate date,
    /** When GPS.bg trip history was last fetched for this vehicle (any day). */
    Instant routesSyncedAt,
    Summary summary,
    List<Segment> segments
) {

    public record Segment(
        boolean moving,
        Instant start,
        Instant end,
        Integer durationS,
        Double startLat,
        Double startLng,
        Double endLat,
        Double endLng,
        Double distanceKm,
        Double avgSpeedKmh,
        Double maxSpeedKmh,
        Double maxAccel,
        Double maxDecel,
        Integer idleS,
        Double fuelEstL,
        String driver,
        String startPlace,
        String endPlace
    ) {}

    /** Day totals. Stops under a minute are not counted: GPS.bg emits zero-length ones. */
    public record Summary(
        double distanceKm,
        long drivingSeconds,
        long stoppedSeconds,
        int trips,
        int stops,
        Double maxSpeedKmh,
        long idleSeconds,
        double fuelEstL
    ) {

        public static Summary of(List<Segment> segments) {
            double km = 0;
            double fuel = 0;
            long driving = 0;
            long stopped = 0;
            long idle = 0;
            int trips = 0;
            int stops = 0;
            Double max = null;
            for (Segment s : segments) {
                long duration = s.durationS() == null ? 0 : s.durationS();
                if (s.moving()) {
                    trips++;
                    driving += duration;
                    km += s.distanceKm() == null ? 0 : s.distanceKm();
                    fuel += s.fuelEstL() == null ? 0 : s.fuelEstL();
                    idle += s.idleS() == null ? 0 : s.idleS();
                    if (s.maxSpeedKmh() != null && (max == null || s.maxSpeedKmh() > max)) max = s.maxSpeedKmh();
                } else {
                    stopped += duration;
                    if (duration >= 60) stops++;
                }
            }
            return new Summary(round2(km), driving, stopped, trips, stops, max, idle, round2(fuel));
        }

        private static double round2(double v) {
            return Math.round(v * 100.0) / 100.0;
        }
    }
}
