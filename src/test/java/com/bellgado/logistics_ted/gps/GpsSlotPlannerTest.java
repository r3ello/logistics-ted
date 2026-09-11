package com.bellgado.logistics_ted.gps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** How the one-call-per-slot budget is split between live status and trip history. */
class GpsSlotPlannerTest {

    private static final List<String> CODES = List.of("A", "B", "C");
    private static final LocalDateTime DAY = LocalDateTime.of(2026, 9, 11, 10, 0);
    private static final LocalDateTime NIGHT = LocalDateTime.of(2026, 9, 11, 2, 0);
    private static final GpsJob STATUS = GpsJob.Status.INSTANCE;

    private final Deque<GpsJob.Routes> queue = new ArrayDeque<>();

    @Test
    void byDayOneSlotInFourFetchesTodaysTripsRoundRobin() {
        GpsSlotPlanner planner = new GpsSlotPlanner(4);

        List<GpsJob> jobs = IntStream.range(0, 12).mapToObj(i -> planner.next(DAY, CODES, queue)).toList();

        LocalDate today = DAY.toLocalDate();
        assertEquals(List.of(
            STATUS, STATUS, STATUS, new GpsJob.Routes("A", today, false),
            STATUS, STATUS, STATUS, new GpsJob.Routes("B", today, false),
            STATUS, STATUS, STATUS, new GpsJob.Routes("C", today, false)), jobs);
    }

    @Test
    void aQueuedRequestTakesTheNextTripSlotAheadOfTheRotation() {
        GpsSlotPlanner planner = new GpsSlotPlanner(4);
        GpsJob.Routes requested = new GpsJob.Routes("C", LocalDate.of(2026, 9, 1), true);
        queue.add(requested);

        List<GpsJob> jobs = IntStream.range(0, 8).mapToObj(i -> planner.next(DAY, CODES, queue)).toList();

        assertEquals(requested, jobs.get(3));
        assertEquals(new GpsJob.Routes("A", DAY.toLocalDate(), false), jobs.get(7));
        assertTrue(queue.isEmpty());
    }

    @Test
    void atNightEachVehicleGetsYesterdayInFullOnceThenTheQueueThenStatus() {
        GpsSlotPlanner planner = new GpsSlotPlanner(4);
        LocalDate yesterday = NIGHT.toLocalDate().minusDays(1);

        GpsJob s1 = planner.next(NIGHT, CODES, queue);
        GpsJob s2 = planner.next(NIGHT, CODES, queue);
        GpsJob s3 = planner.next(NIGHT, CODES, queue);
        GpsJob s4 = planner.next(NIGHT, CODES, queue);
        GpsJob s5 = planner.next(NIGHT, CODES, queue);
        GpsJob.Routes requested = new GpsJob.Routes("B", LocalDate.of(2026, 9, 1), true);
        queue.add(requested);
        GpsJob s6 = planner.next(NIGHT, CODES, queue);
        GpsJob s7 = planner.next(NIGHT, CODES, queue);

        assertEquals(new GpsJob.Routes("A", yesterday, true), s1);
        assertEquals(new GpsJob.Routes("B", yesterday, true), s2);
        assertEquals(new GpsJob.Routes("C", yesterday, true), s3);
        assertEquals(STATUS, s4);        // status keeps one slot in four at night
        assertEquals(STATUS, s5);        // everything reconciled, nothing queued
        assertEquals(requested, s6);
        assertEquals(STATUS, s7);
    }

    @Test
    void theNextNightReconcilesTheNextDay() {
        GpsSlotPlanner planner = new GpsSlotPlanner(4);
        IntStream.range(0, 4).forEach(i -> planner.next(NIGHT, CODES, queue));   // A, B, C, then status

        GpsJob nextNight = planner.next(NIGHT.plusDays(1), CODES, queue);

        assertEquals(new GpsJob.Routes("A", NIGHT.toLocalDate(), true), nextNight);
    }

    @Test
    void withNoVehiclesKnownYetEverySlotIsStatus() {
        GpsSlotPlanner planner = new GpsSlotPlanner(4);

        List<GpsJob> jobs = IntStream.range(0, 8)
            .mapToObj(i -> planner.next(i < 4 ? DAY : NIGHT, List.of(), queue)).toList();

        assertTrue(jobs.stream().allMatch(j -> j == STATUS));
    }

    @Test
    void nightIsOneToSix() {
        assertFalse(GpsSlotPlanner.isNight(LocalTime.of(0, 59)));
        assertTrue(GpsSlotPlanner.isNight(LocalTime.of(1, 0)));
        assertTrue(GpsSlotPlanner.isNight(LocalTime.of(5, 59)));
        assertFalse(GpsSlotPlanner.isNight(LocalTime.of(6, 0)));
    }
}
