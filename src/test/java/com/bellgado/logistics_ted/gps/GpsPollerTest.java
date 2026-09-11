package com.bellgado.logistics_ted.gps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The poller's contract with GPS.bg's one-call-per-181-s rule: what each slot calls, how failures
 * back off, and that nothing escapes a scheduled run. Slots are driven by hand with a fake clock.
 */
class GpsPollerTest {

    /** 10:00 in Sofia (EEST, UTC+3): daytime. */
    private static final Instant DAYTIME = Instant.parse("2026-09-11T07:00:00Z");

    private final GpsHunterProperties props =
        new GpsHunterProperties(true, "https://gps.example/soap", "u", "p", 200, 4, "Europe/Sofia", 60);
    private GpsHunterClient client;
    private VehicleTrackingService tracking;
    private GpsSyncState state;
    private MutableClock clock;
    private GpsPoller poller;

    @BeforeEach
    void setUp() {
        client = mock(GpsHunterClient.class);
        tracking = mock(VehicleTrackingService.class);
        state = new GpsSyncState(props);
        clock = new MutableClock(DAYTIME);
        when(tracking.activeCodes()).thenReturn(List.of("A"));
        poller = new GpsPoller(props, client, tracking, state, clock);
    }

    @Test
    void aStatusSlotStoresWhatGpsBgReturned() {
        List<VehicleStatus> statuses = List.of();
        when(client.getStatus()).thenReturn(statuses);

        poller.runSlot();

        verify(tracking).applyStatus(statuses, DAYTIME);
        assertEquals(DAYTIME, state.snapshot().lastStatusAt());
        assertNull(state.throttledUntil());
    }

    @Test
    void theFourthDaytimeSlotFetchesTodaySoFar() {
        when(client.getStatus()).thenReturn(List.of());

        for (int i = 0; i < 4; i++) poller.runSlot();

        verify(client, times(3)).getStatus();
        verify(client).vehicleRoutes("A", LocalDateTime.of(2026, 9, 11, 0, 0), LocalDateTime.of(2026, 9, 11, 10, 0));
        verify(tracking).applyRoutes(eq("A"), eq(Instant.parse("2026-09-10T21:00:00Z")),
            eq(Instant.parse("2026-09-11T07:00:00Z")), anyList(), eq(DAYTIME));
        assertEquals(DAYTIME, state.snapshot().lastRoutesAt());
    }

    @Test
    void aThrottleFaultBacksOffAndSkippedSlotsNeverCallGpsBg() {
        when(client.getStatus()).thenThrow(new GpsHunterException(GpsHunterException.THROTTLED, "Error !!!"));

        poller.runSlot();                                  // failure #1: wait one slot
        assertEquals(DAYTIME.plusSeconds(200), state.throttledUntil());

        clock.advance(Duration.ofSeconds(200));
        poller.runSlot();                                  // allowed again; failure #2: wait two slots
        assertEquals(clock.instant().plusSeconds(400), state.throttledUntil());

        clock.advance(Duration.ofSeconds(200));
        poller.runSlot();                                  // still inside the back-off: skipped

        verify(client, times(2)).getStatus();
        assertEquals(GpsHunterException.THROTTLED, state.snapshot().lastErrorCode());
    }

    @Test
    void aRejectedAccountPausesPollingForHalfAnHour() {
        when(client.getStatus()).thenThrow(new GpsHunterException(GpsHunterException.INVALID_CREDENTIALS, "Error !!!"));

        poller.runSlot();

        assertEquals(DAYTIME.plus(Duration.ofMinutes(30)), state.throttledUntil());
    }

    @Test
    void aSuccessEndsTheBackOff() {
        when(client.getStatus())
            .thenThrow(new GpsHunterException(GpsHunterException.TRANSPORT, "timeout"))
            .thenReturn(List.of());

        poller.runSlot();
        clock.advance(Duration.ofSeconds(200));
        poller.runSlot();

        assertNull(state.throttledUntil());
        assertNotNull(state.snapshot().lastStatusAt());
    }

    @Test
    void aNightlyReconciliationLostToABlipIsQueuedForRetry() {
        clock.set(Instant.parse("2026-09-10T23:30:00Z"));     // 02:30 in Sofia: night
        when(client.vehicleRoutes(eq("A"), any(), any()))
            .thenThrow(new GpsHunterException(GpsHunterException.TRANSPORT, "timeout"));

        poller.runSlot();

        verify(client).vehicleRoutes("A", LocalDateTime.of(2026, 9, 10, 0, 0), LocalDateTime.of(2026, 9, 10, 23, 59, 59));
        assertEquals(1, state.snapshot().queued());
    }

    @Test
    void aStorageFailureIsReportedButNeverEscapesTheScheduledRun() {
        when(client.getStatus()).thenReturn(List.of());
        when(tracking.applyStatus(any(), any())).thenThrow(new IllegalStateException("db down"));

        poller.tick();   // must not throw: that would cancel every future run

        assertTrue(state.snapshot().lastError().contains("db down"));
        assertNull(state.throttledUntil());   // not GPS.bg's fault: no back-off
    }

    @Test
    void nothingIsScheduledWithoutCredentials() {
        GpsHunterProperties noCredentials =
            new GpsHunterProperties(true, "https://gps.example/soap", "", "", 200, 4, "Europe/Sofia", 60);
        GpsSyncState s = new GpsSyncState(noCredentials);
        GpsPoller p = new GpsPoller(noCredentials, client, tracking, s, clock);

        p.start();

        verify(client, never()).getStatus();
        assertTrue(s.snapshot().lastError().contains("not configured"));
        p.stop();
    }

    @Test
    void backOffDoublesPerFailureUpToHalfAnHour() {
        GpsHunterException throttled = new GpsHunterException(GpsHunterException.THROTTLED, null);

        assertEquals(Duration.ofSeconds(200), GpsPoller.backoff(throttled, 1, 200));
        assertEquals(Duration.ofSeconds(400), GpsPoller.backoff(throttled, 2, 200));
        assertEquals(Duration.ofSeconds(1600), GpsPoller.backoff(throttled, 4, 200));
        assertEquals(Duration.ofSeconds(1800), GpsPoller.backoff(throttled, 9, 200));
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        void set(Instant instant) {
            now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
