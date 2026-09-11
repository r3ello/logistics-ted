package com.bellgado.logistics_ted.gps;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Decides what each provider slot is spent on. Pure — no clock, no I/O: the poller passes the local
 * time in the provider's zone, the active vehicle codes and the on-demand queue. Only ever used from
 * the poller's single thread.
 *
 * <p><b>Day</b> (06:00-01:00): one slot in {@code routeSlotEvery} fetches one vehicle's trips for today
 * (a queued on-demand request first, else the next vehicle round-robin); the rest refresh live status.
 * With 7 vehicles, 200 s slots and 1-in-4: status every ~4.4 min on average, each vehicle's trips
 * refreshed every ~93 min.
 *
 * <p><b>Night</b> (01:00-06:00): the ratio flips. Every active vehicle's previous calendar day is
 * fetched once in full, then queued requests drain; status still runs one slot in
 * {@code routeSlotEvery}. It starts at 01:00, not midnight, so yesterday's late trips — which the
 * "today so far" windows stop covering at 00:00 — are all in by then.
 */
final class GpsSlotPlanner {

    static final LocalTime NIGHT_START = LocalTime.of(1, 0);
    static final LocalTime NIGHT_END = LocalTime.of(6, 0);

    private final int routeSlotEvery;
    private long slot;
    private int cursor;
    private LocalDate reconcilingDay;
    private final Set<String> reconciled = new HashSet<>();

    GpsSlotPlanner(int routeSlotEvery) {
        this.routeSlotEvery = routeSlotEvery;
    }

    GpsJob next(LocalDateTime now, List<String> activeCodes, Deque<GpsJob.Routes> queue) {
        slot++;
        if (activeCodes.isEmpty() && queue.isEmpty()) {
            return GpsJob.Status.INSTANCE;   // nothing known yet: status is also how vehicles are discovered
        }
        boolean everyNth = slot % routeSlotEvery == 0;

        if (isNight(now.toLocalTime())) {
            if (everyNth) return GpsJob.Status.INSTANCE;
            LocalDate yesterday = now.toLocalDate().minusDays(1);
            if (!yesterday.equals(reconcilingDay)) {
                reconcilingDay = yesterday;
                reconciled.clear();
            }
            for (String code : activeCodes) {
                if (reconciled.add(code)) return new GpsJob.Routes(code, yesterday, true);
            }
            GpsJob.Routes queued = queue.poll();
            return queued != null ? queued : GpsJob.Status.INSTANCE;
        }

        if (!everyNth) return GpsJob.Status.INSTANCE;
        GpsJob.Routes queued = queue.poll();
        if (queued != null) return queued;
        if (activeCodes.isEmpty()) return GpsJob.Status.INSTANCE;
        String code = activeCodes.get(Math.floorMod(cursor++, activeCodes.size()));
        return new GpsJob.Routes(code, now.toLocalDate(), false);
    }

    static boolean isNight(LocalTime t) {
        return !t.isBefore(NIGHT_START) && t.isBefore(NIGHT_END);
    }
}
