package com.bellgado.logistics_ted.gps;

import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

/**
 * The only code that calls GPS.bg: one call per slot, one slot every {@code gps.slot-seconds}, on a
 * private single-thread scheduler — never from a request thread.
 *
 * <p>GPS.bg allows one call per 181 s per account and restarts that timer on every rejected attempt
 * (fault 11). Hence: fixed <i>delay</i> (the next call is a full slot after the previous one ended,
 * however slow the answer), exponential back-off on failures, and a first call one full slot after
 * boot so a quick redeploy does not land inside the previous instance's window.
 *
 * <p>Only exists with {@code gps.enabled=true}, and exactly one environment may have that: two
 * pollers on the same account keep resetting each other's timer and both starve.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "gps", name = "enabled", havingValue = "true")
public class GpsPoller {

    /** Back-off ceiling, and the pause after GPS.bg rejects the account itself. */
    static final Duration MAX_BACKOFF = Duration.ofMinutes(30);

    private final GpsHunterProperties props;
    private final GpsHunterClient client;
    private final VehicleTrackingService tracking;
    private final GpsSyncState state;
    private final Clock clock;
    private final GpsSlotPlanner planner;
    private ThreadPoolTaskScheduler scheduler;

    @Autowired
    public GpsPoller(GpsHunterProperties props, GpsHunterClient client,
                     VehicleTrackingService tracking, GpsSyncState state) {
        this(props, client, tracking, state, Clock.systemUTC());
    }

    GpsPoller(GpsHunterProperties props, GpsHunterClient client,
              VehicleTrackingService tracking, GpsSyncState state, Clock clock) {
        this.props = props;
        this.client = client;
        this.tracking = tracking;
        this.state = state;
        this.clock = clock;
        this.planner = new GpsSlotPlanner(props.effectiveRouteSlotEvery());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!props.usable()) {
            log.warn("gps.enabled=true but GPS_USER / GPS_PASSWORD are not set: GPS polling stays off.");
            state.recordProblem("GPS.bg credentials are not configured (GPS_USER / GPS_PASSWORD).", clock.instant());
            return;
        }
        Duration slot = Duration.ofSeconds(props.effectiveSlotSeconds());
        // Private, not a bean: a TaskScheduler bean would become the whole app's default scheduler.
        ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
        s.setPoolSize(1);
        s.setThreadNamePrefix("gps-poller-");
        s.setWaitForTasksToCompleteOnShutdown(false);
        s.initialize();
        s.scheduleWithFixedDelay(this::tick, clock.instant().plus(slot), slot);
        scheduler = s;
        log.info("GPS polling started: one GPS.bg call every {}s; by day 1 slot in {} fetches trips.",
            slot.toSeconds(), props.effectiveRouteSlotEvery());
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null) scheduler.shutdown();
    }

    /** Scheduler entry point. Anything escaping a scheduled task cancels all of its future runs. */
    void tick() {
        try {
            runSlot();
        } catch (Throwable t) {
            log.error("GPS slot failed unexpectedly", t);
        }
    }

    void runSlot() {
        Instant now = clock.instant();
        Instant until = state.throttledUntil();
        if (until != null && now.isBefore(until)) {
            log.debug("GPS slot skipped: backing off until {}", until);
            return;
        }
        ZoneId zone = props.zone();
        LocalDateTime local = LocalDateTime.ofInstant(now, zone).truncatedTo(ChronoUnit.SECONDS);
        GpsJob job = planner.next(local, tracking.activeCodes(), state.queue());
        try {
            if (job instanceof GpsJob.Routes routes) {
                LocalDateTime from = routes.day().atStartOfDay();
                LocalDateTime endOfDay = routes.day().atTime(23, 59, 59);
                LocalDateTime to = routes.fullDay() || local.isAfter(endOfDay) ? endOfDay : local;
                if (to.isAfter(from)) {
                    List<RouteSegment> segments = client.vehicleRoutes(routes.vehicleCode(), from, to);
                    int stored = tracking.applyRoutes(routes.vehicleCode(),
                        from.atZone(zone).toInstant(), to.atZone(zone).toInstant(), segments, now);
                    log.debug("GPS trips {} {}: {} segments", routes.vehicleCode(), routes.day(), stored);
                } else {
                    // Exactly 00:00:00: an empty window (GPS.bg would answer fault 10). Spend it on status.
                    job = GpsJob.Status.INSTANCE;
                    syncStatus(now);
                }
            } else {
                syncStatus(now);
            }
            state.recordSuccess(job, now);
        } catch (GpsHunterException e) {
            int failures = state.recordFailure(e, now);
            Duration wait = backoff(e, failures, props.effectiveSlotSeconds());
            state.backOffUntil(now.plus(wait));
            if (job instanceof GpsJob.Routes routes && routes.fullDay() && e.isTransient()) {
                state.requeueFront(routes);   // a night's reconciliation must not be lost to a blip
            }
            logFailure(e, failures, wait);
        } catch (RuntimeException e) {
            // GPS.bg answered; storing the answer failed. Not the provider's fault, so no back-off.
            state.recordProblem("Storing GPS data failed: " + e.getMessage(), now);
            log.error("Storing GPS.bg data failed", e);
        }
    }

    private void syncStatus(Instant now) {
        List<VehicleStatus> statuses = client.getStatus();
        int added = tracking.applyStatus(statuses, now);
        log.debug("GPS status: {} vehicles, {} new positions", statuses.size(), added);
    }

    /** One slot after the first failure, then doubling to {@link #MAX_BACKOFF}. */
    static Duration backoff(GpsHunterException e, int failures, int slotSeconds) {
        if (e.isCredentialProblem()) return MAX_BACKOFF;
        long seconds = (long) slotSeconds << Math.min(Math.max(failures, 1) - 1, 4);
        return Duration.ofSeconds(Math.min(seconds, MAX_BACKOFF.toSeconds()));
    }

    private void logFailure(GpsHunterException e, int failures, Duration wait) {
        if (e.isThrottled()) {
            log.warn("GPS.bg refused the call as too frequent (fault 11), failure #{}; next attempt in {}s. "
                + "If this repeats, another environment or script is using the same GPS.bg account; only one may.",
                failures, wait.toSeconds());
        } else if (e.isCredentialProblem()) {
            log.error("GPS.bg rejected the account: {}. Polling paused for {} min; check GPS_USER / GPS_PASSWORD.",
                e.getMessage(), wait.toMinutes());
        } else {
            log.warn("{}; failure #{}, next attempt in {}s.", e.getMessage(), failures, wait.toSeconds());
        }
    }
}
