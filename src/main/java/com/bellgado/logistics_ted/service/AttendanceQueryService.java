package com.bellgado.logistics_ted.service;

import com.bellgado.logistics_ted.domain.Crew;
import com.bellgado.logistics_ted.domain.WorkSession;
import com.bellgado.logistics_ted.domain.Worker;
import com.bellgado.logistics_ted.repository.WorkSessionRepository;
import com.bellgado.logistics_ted.repository.WorkerRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only view over the QR check-in log ({@code work_session}) for reporting — used by the
 * Telegram agent's attendance tools. Never writes: sessions are created/closed only by the
 * public check-in page.
 *
 * <p>How minutes are counted (the one place this rule lives for reports):
 * <ul>
 *   <li>{@link State#CLOSED} — check-in to check-out.</li>
 *   <li>{@link State#IN_PROGRESS} — still open and dated today: check-in to now.</li>
 *   <li>{@link State#MISSING_CHECKOUT} — still open from a past day: counts 0, flagged, because
 *       the real end time is unknown and "until now" would inflate it by days.</li>
 * </ul>
 * Days are the work day in {@code app.timezone} ({@code session_date}, set at check-in).
 */
@Service
public class AttendanceQueryService {

    /** Upper bound on a report range, so a typo like 2000-01-01 can't scan the whole table. */
    public static final int MAX_RANGE_DAYS = 366;

    public enum State { CLOSED, IN_PROGRESS, MISSING_CHECKOUT }

    public record SessionView(int sessionId,
                              int workerId, String workerName,
                              Integer crewId, String crewName,
                              int houseId, String houseName,
                              LocalDate date,
                              ZonedDateTime checkedInAt,
                              ZonedDateTime checkedOutAt,
                              State state,
                              long countedMinutes) {}

    public record WorkerRef(int id, String name, String role, Integer crewId, String crewName) {}

    private final WorkSessionRepository sessions;
    private final WorkerRepository workers;
    private final ZoneId zone;
    private final Clock clock;

    @Autowired
    public AttendanceQueryService(WorkSessionRepository sessions,
                                  WorkerRepository workers,
                                  @Value("${app.timezone:Europe/Sofia}") String timezone) {
        this(sessions, workers, ZoneId.of(timezone), Clock.systemUTC());
    }

    AttendanceQueryService(WorkSessionRepository sessions, WorkerRepository workers,
                           ZoneId zone, Clock clock) {
        this.sessions = sessions;
        this.workers = workers;
        this.zone = zone;
        this.clock = clock;
    }

    public ZoneId zone() {
        return zone;
    }

    /** Today's work day in the app timezone. */
    public LocalDate today() {
        return LocalDate.now(clock.withZone(zone));
    }

    /**
     * Sessions dated between {@code from} and {@code to} (inclusive), optionally narrowed to one
     * worker / house / crew (null = any), ordered by check-in time.
     */
    @Transactional(readOnly = true)
    public List<SessionView> sessionsBetween(LocalDate from, LocalDate to,
                                             Integer workerId, Integer houseId, Integer crewId) {
        validateRange(from, to);
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(zone));
        return sessions.findAllInRange(from, to).stream()
                .filter(s -> workerId == null || Objects.equals(s.getWorker().getId(), workerId))
                .filter(s -> houseId == null || Objects.equals(s.getHouse().getId(), houseId))
                .filter(s -> crewId == null || Objects.equals(crewIdOf(s.getWorker()), crewId))
                .map(s -> toView(s, now))
                .toList();
    }

    /** Every session never checked out, on any date, oldest first. */
    @Transactional(readOnly = true)
    public List<SessionView> openSessions() {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(zone));
        return sessions.findAllOpen().stream().map(s -> toView(s, now)).toList();
    }

    /**
     * Workers in a crew (or, with {@code crewId == null}, every worker assigned to some crew) who
     * have no session at all on {@code date}. Workers without a crew are not expected on a site,
     * so they are never reported as absent.
     */
    @Transactional(readOnly = true)
    public List<WorkerRef> absentWorkers(LocalDate date, Integer crewId) {
        List<Worker> roster = crewId != null
                ? workers.findByCrewId(crewId)
                : workers.findAllWithCrew().stream().filter(w -> w.getCrew() != null).toList();
        Set<Integer> present = new HashSet<>();
        for (WorkSession s : sessions.findAllInRange(date, date)) {
            present.add(s.getWorker().getId());
        }
        return roster.stream()
                .filter(w -> !present.contains(w.getId()))
                .sorted(Comparator.comparing((Worker w) -> crewNameOf(w), Comparator.nullsLast(String::compareTo))
                        .thenComparing(Worker::getName, Comparator.nullsLast(String::compareTo)))
                .map(w -> new WorkerRef(w.getId(), w.getName(),
                        w.getRole() != null ? w.getRole().name() : null, crewIdOf(w), crewNameOf(w)))
                .toList();
    }

    SessionView toView(WorkSession s, ZonedDateTime now) {
        ZonedDateTime in = s.getCheckedInAt().atZoneSameInstant(zone);
        ZonedDateTime out = s.getCheckedOutAt() != null ? s.getCheckedOutAt().atZoneSameInstant(zone) : null;
        State state;
        long minutes;
        if (out != null) {
            state = State.CLOSED;
            minutes = ChronoUnit.MINUTES.between(in, out);
        } else if (s.getSessionDate().equals(now.toLocalDate())) {
            state = State.IN_PROGRESS;
            minutes = Math.max(0, Duration.between(in, now).toMinutes());
        } else {
            state = State.MISSING_CHECKOUT;
            minutes = 0;
        }
        Worker w = s.getWorker();
        return new SessionView(s.getId(),
                w.getId(), w.getName(), crewIdOf(w), crewNameOf(w),
                s.getHouse().getId(), s.getHouse().getName(),
                s.getSessionDate(), in, out, state, minutes);
    }

    private static void validateRange(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("Both from and to dates are required.");
        }
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("The end date " + to + " is before the start date " + from + ".");
        }
        if (ChronoUnit.DAYS.between(from, to) >= MAX_RANGE_DAYS) {
            throw new IllegalArgumentException("Date range too large (max " + MAX_RANGE_DAYS + " days).");
        }
    }

    private static Integer crewIdOf(Worker w) {
        Crew c = w.getCrew();
        return c != null ? c.getId() : null;
    }

    private static String crewNameOf(Worker w) {
        Crew c = w.getCrew();
        return c != null ? c.getName() : null;
    }
}
