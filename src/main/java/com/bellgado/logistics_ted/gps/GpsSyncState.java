package com.bellgado.logistics_ted.gps;

import java.time.Instant;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * In-memory state shared by the poller and the REST API: when GPS.bg last answered, the last error,
 * the back-off deadline, and the queue of on-demand trip fetches. Exists in every environment (the
 * sync-status endpoint reports "disabled" where the poller is off). Lost on restart by design — the
 * queue is a convenience, and the nightly reconciliation covers anything dropped.
 */
@Component
public class GpsSyncState {

    public static final int MAX_QUEUE = 100;

    private final GpsHunterProperties props;
    private final Deque<GpsJob.Routes> queue = new ConcurrentLinkedDeque<>();
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile Instant lastStatusAt;
    private volatile Instant lastRoutesAt;
    private volatile Instant lastErrorAt;
    private volatile String lastError;
    private volatile Integer lastErrorCode;
    private volatile Instant backoffUntil;

    public GpsSyncState(GpsHunterProperties props) {
        this.props = props;
    }

    public record Snapshot(
        boolean enabled,
        int slotSeconds,
        Instant lastStatusAt,
        Instant lastRoutesAt,
        String lastError,
        Integer lastErrorCode,
        Instant lastErrorAt,
        Instant backoffUntil,
        int queued
    ) {}

    public Snapshot snapshot() {
        return new Snapshot(props.usable(), props.effectiveSlotSeconds(), lastStatusAt, lastRoutesAt,
            lastError, lastErrorCode, lastErrorAt, backoffUntil, queue.size());
    }

    /**
     * Queues a trip fetch. Returns its 1-based position; asking again for the same vehicle and day
     * keeps the existing place. Returns -1 when the queue is full.
     */
    public synchronized int enqueue(GpsJob.Routes job) {
        int position = 1;
        for (GpsJob.Routes queued : queue) {
            if (queued.sameTarget(job)) return position;
            position++;
        }
        if (queue.size() >= MAX_QUEUE) return -1;
        queue.offerLast(job);
        return queue.size();
    }

    synchronized void requeueFront(GpsJob.Routes job) {
        for (GpsJob.Routes queued : queue) {
            if (queued.sameTarget(job)) return;
        }
        queue.offerFirst(job);
    }

    Deque<GpsJob.Routes> queue() {
        return queue;
    }

    void recordSuccess(GpsJob job, Instant at) {
        if (job instanceof GpsJob.Routes) lastRoutesAt = at;
        else lastStatusAt = at;
        consecutiveFailures.set(0);
        backoffUntil = null;
    }

    /** Returns how many calls in a row have now failed. */
    int recordFailure(GpsHunterException e, Instant at) {
        lastError = e.getMessage();
        lastErrorCode = e.code();
        lastErrorAt = at;
        return consecutiveFailures.incrementAndGet();
    }

    /** A problem on our side (config, storage) — reported, but not counted against GPS.bg. */
    void recordProblem(String message, Instant at) {
        lastError = message;
        lastErrorCode = null;
        lastErrorAt = at;
    }

    void backOffUntil(Instant until) {
        backoffUntil = until;
    }

    Instant throttledUntil() {
        return backoffUntil;
    }
}
