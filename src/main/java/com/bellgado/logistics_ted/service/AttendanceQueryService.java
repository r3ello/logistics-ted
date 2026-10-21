package com.bellgado.logistics_ted.service;

import com.bellgado.logistics_ted.domain.Crew;
import com.bellgado.logistics_ted.domain.HouseStageCrewLog;
import com.bellgado.logistics_ted.domain.WorkSession;
import com.bellgado.logistics_ted.domain.Worker;
import com.bellgado.logistics_ted.repository.HouseStageCrewLogRepository;
import com.bellgado.logistics_ted.repository.HouseStageRepository;
import com.bellgado.logistics_ted.repository.WorkSessionRepository;
import com.bellgado.logistics_ted.repository.WorkerRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
                              int workerId, String workerName, String workerExternalId,
                              Integer crewId, String crewName, String crewExternalId,
                              int houseId, String houseName, String houseExternalId, String houseLabel,
                              LocalDate date,
                              ZonedDateTime checkedInAt,
                              ZonedDateTime checkedOutAt,
                              State state,
                              long countedMinutes,
                              Integer stageOrder, String stageName, String stageNameEn) {}

    public record WorkerRef(int id, String name, String role, Integer crewId, String crewName) {}

    private final WorkSessionRepository sessions;
    private final WorkerRepository workers;
    private final HouseStageCrewLogRepository stageLogs;
    private final HouseStageRepository houseStages;
    private final ZoneId zone;
    private final Clock clock;

    @Autowired
    public AttendanceQueryService(WorkSessionRepository sessions,
                                  WorkerRepository workers,
                                  HouseStageCrewLogRepository stageLogs,
                                  HouseStageRepository houseStages,
                                  @Value("${app.timezone:Europe/Sofia}") String timezone) {
        this(sessions, workers, stageLogs, houseStages, ZoneId.of(timezone), Clock.systemUTC());
    }

    AttendanceQueryService(WorkSessionRepository sessions, WorkerRepository workers,
                           HouseStageCrewLogRepository stageLogs, HouseStageRepository houseStages,
                           ZoneId zone, Clock clock) {
        this.sessions    = sessions;
        this.workers     = workers;
        this.stageLogs   = stageLogs;
        this.houseStages = houseStages;
        this.zone        = zone;
        this.clock       = clock;
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
        // Pre-load IN_PROGRESS log entries for historical stage lookup (key: crewId|houseId → list sorted by start_date ASC).
        Map<String, List<HouseStageCrewLog>> stageMap = new HashMap<>();
        for (HouseStageCrewLog l : stageLogs.findInProgressUpTo(to.toString())) {
            stageMap.computeIfAbsent(l.getCrewId() + "|" + l.getHouseId(), k -> new java.util.ArrayList<>()).add(l);
        }
        // Fallback: current ASSIGNED/IN_PROGRESS stages from house_stage for crews with no log entry.
        // Key: crewId|houseId → [stageOrder, stageName, stageNameEn]
        Map<String, Object[]> stageFallback = new HashMap<>();
        for (Object[] row : houseStages.findActiveStagesForAllCrews()) {
            if (row[0] == null || row[1] == null) continue;
            String key = ((Number) row[0]).intValue() + "|" + ((Number) row[1]).intValue();
            stageFallback.putIfAbsent(key, row); // first row wins (lowest stage_order)
        }
        return sessions.findAllInRange(from, to).stream()
                .filter(s -> workerId == null || Objects.equals(s.getWorker().getId(), workerId))
                .filter(s -> houseId == null || Objects.equals(s.getHouse().getId(), houseId))
                .filter(s -> crewId == null || Objects.equals(crewIdOf(s.getWorker()), crewId))
                .map(s -> toView(s, now, stageMap, stageFallback))
                .toList();
    }

    /** Every session never checked out, on any date, oldest first. */
    @Transactional(readOnly = true)
    public List<SessionView> openSessions() {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(zone));
        return sessions.findAllOpen().stream().map(s -> toView(s, now, Map.of(), Map.of())).toList();
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

    SessionView toView(WorkSession s, ZonedDateTime now, Map<String, List<HouseStageCrewLog>> stageMap,
                       Map<String, Object[]> stageFallback) {
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
        Integer crewId = crewIdOf(w);

        // Find the stage for this crew+house: prefer the most recent IN_PROGRESS log entry on or before
        // the session date; fall back to the current ASSIGNED/IN_PROGRESS stage from house_stage.
        Integer stageOrder = null; String stageName = null; String stageNameEn = null;
        if (crewId != null) {
            String key = crewId + "|" + s.getHouse().getId();
            List<HouseStageCrewLog> entries = stageMap.get(key);
            if (entries != null) {
                HouseStageCrewLog match = null;
                for (HouseStageCrewLog l : entries) {
                    if (!l.getStartDate().isAfter(s.getSessionDate())) match = l;
                    else break;
                }
                if (match != null) {
                    stageOrder  = match.getStageOrder();
                    stageName   = match.getStageName();
                    stageNameEn = match.getStageNameEn();
                }
            }
            if (stageName == null) {
                Object[] row = stageFallback.get(key);
                if (row != null) {
                    stageOrder  = row[2] != null ? ((Number) row[2]).intValue() : null;
                    stageName   = row[3] != null ? row[3].toString() : null;
                    stageNameEn = row[4] != null ? row[4].toString() : null;
                }
            }
        }

        String extId = s.getHouse().getExternalId();
        String hName = s.getHouse().getName();
        String houseLabel = (extId != null && !extId.isBlank()) ? extId + " " + hName : hName;
        Crew crew = w.getCrew();
        return new SessionView(s.getId(),
                w.getId(), w.getName(), w.getExternalId(),
                crewId, crewNameOf(w), crew != null ? crew.getExternalId() : null,
                s.getHouse().getId(), hName, extId, houseLabel,
                s.getSessionDate(), in, out, state, minutes,
                stageOrder, stageName, stageNameEn);
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
