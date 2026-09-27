package com.bellgado.logistics_ted.service.exporter.impl;

import static com.bellgado.logistics_ted.service.exporter.ExportColumnType.BOOLEAN;
import static com.bellgado.logistics_ted.service.exporter.ExportColumnType.DATE;
import static com.bellgado.logistics_ted.service.exporter.ExportColumnType.DECIMAL;
import static com.bellgado.logistics_ted.service.exporter.ExportColumnType.INTEGER;
import static com.bellgado.logistics_ted.service.exporter.ExportColumnType.PIPE_SET;
import static com.bellgado.logistics_ted.service.exporter.ExportColumnType.TEXT;
import static com.bellgado.logistics_ted.service.exporter.ExportColumnType.TIMESTAMP;

import com.bellgado.logistics_ted.service.AttendanceQueryService;
import com.bellgado.logistics_ted.service.AttendanceQueryService.SessionView;
import com.bellgado.logistics_ted.service.AttendanceQueryService.State;
import com.bellgado.logistics_ted.service.exporter.EntityExporter;
import com.bellgado.logistics_ted.service.exporter.ExportColumn;
import com.bellgado.logistics_ted.service.exporter.ExportFilter;
import com.bellgado.logistics_ted.service.exporter.ExportQuery;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Hours per worker per work day — the timesheet shape payroll actually asks for. It aggregates the
 * exact sessions {@link AttendanceSessionExporter} exports, so the two files always reconcile: the
 * sum of {@code counted_minutes} here equals the sum there for the same filters.
 *
 * <p>Only days with at least one check-in produce a row. Absences are not synthesised: "expected to
 * work" is not something the check-in log knows.
 */
@Component
public class AttendanceDailyExporter implements EntityExporter<AttendanceDailyExporter.DailyRow> {

    /** One worker on one day. */
    public record DailyRow(LocalDate date,
                           int workerId, String workerName,
                           Integer crewId, String crewName,
                           int sessions,
                           List<String> houseNames, List<String> houseExternalIds,
                           ZonedDateTime firstCheckIn,
                           ZonedDateTime lastCheckOut,
                           long countedMinutes,
                           boolean missingCheckout,
                           boolean inProgress) {}

    private static final List<ExportColumn<DailyRow>> COLUMNS = List.of(
        ExportColumn.of("date", DATE, "Work day (Europe/Sofia).", DailyRow::date),
        ExportColumn.of("worker_id", INTEGER, "Internal worker id.", DailyRow::workerId),
        ExportColumn.of("worker_name", TEXT, "Worker's name.", DailyRow::workerName),
        ExportColumn.optional("crew_id", INTEGER, "Internal id of the worker's current crew.", DailyRow::crewId),
        ExportColumn.of("crew_name", TEXT, "Worker's current crew.", DailyRow::crewName),
        ExportColumn.of("sessions", INTEGER, "Number of check-ins that day.", DailyRow::sessions),
        ExportColumn.of("houses", PIPE_SET, "Houses worked at, in check-in order, pipe-separated.",
            DailyRow::houseNames),
        ExportColumn.optional("house_external_ids", PIPE_SET,
            "External IDs of those houses, pipe-separated (houses without one are skipped).",
            DailyRow::houseExternalIds),
        ExportColumn.of("first_check_in", TIMESTAMP, "Earliest check-in.", DailyRow::firstCheckIn),
        ExportColumn.of("last_check_out", TIMESTAMP,
            "Latest check-out; empty if any session that day is still open.", DailyRow::lastCheckOut),
        ExportColumn.of("counted_minutes", INTEGER, "Sum of the sessions' counted minutes.",
            DailyRow::countedMinutes),
        ExportColumn.of("counted_hours", DECIMAL, "counted_minutes / 60, two decimals.",
            r -> hours(r.countedMinutes())),
        ExportColumn.of("missing_checkout", BOOLEAN,
            "True if a session that day was never checked out (it counts 0 minutes).",
            DailyRow::missingCheckout),
        ExportColumn.optional("in_progress", BOOLEAN, "True if the worker is checked in right now.",
            DailyRow::inProgress));

    private final AttendanceQueryService attendance;

    public AttendanceDailyExporter(AttendanceQueryService attendance) {
        this.attendance = attendance;
    }

    @Override
    public String name() {
        return "attendance-daily";
    }

    @Override
    public String description() {
        return "Timesheet: one row per worker per work day with at least one check-in, hours summed.";
    }

    @Override
    public List<ExportColumn<DailyRow>> columns() {
        return COLUMNS;
    }

    @Override
    public List<ExportFilter> filters() {
        return AttendanceSessionExporter.FILTERS;
    }

    @Override
    public List<DailyRow> fetch(ExportQuery q) {
        return aggregate(AttendanceSessionExporter.sessions(attendance, q));
    }

    @Override
    public String fileName(ExportQuery q) {
        return name() + "_" + q.date("from") + "_" + q.date("to") + ".csv";
    }

    /** Groups sessions by (day, worker), ordered by day then worker name. Pure — unit-tested. */
    static List<DailyRow> aggregate(List<SessionView> sessions) {
        record Key(LocalDate date, int workerId) {}
        Map<Key, List<SessionView>> groups = new LinkedHashMap<>();
        for (SessionView s : sessions) {
            groups.computeIfAbsent(new Key(s.date(), s.workerId()), k -> new ArrayList<>()).add(s);
        }

        List<DailyRow> rows = new ArrayList<>();
        for (List<SessionView> day : groups.values()) {
            day.sort(Comparator.comparing(SessionView::checkedInAt));
            SessionView first = day.get(0);

            Set<String> houses = new LinkedHashSet<>();
            Set<String> externalIds = new LinkedHashSet<>();
            long minutes = 0;
            boolean anyOpen = false, missing = false, inProgress = false;
            ZonedDateTime lastOut = null;
            for (SessionView s : day) {
                if (s.houseName() != null) houses.add(s.houseName());
                if (s.houseExternalId() != null && !s.houseExternalId().isBlank()) externalIds.add(s.houseExternalId());
                minutes += s.countedMinutes();
                if (s.checkedOutAt() == null) anyOpen = true;
                else if (lastOut == null || s.checkedOutAt().isAfter(lastOut)) lastOut = s.checkedOutAt();
                missing |= s.state() == State.MISSING_CHECKOUT;
                inProgress |= s.state() == State.IN_PROGRESS;
            }

            rows.add(new DailyRow(first.date(), first.workerId(), first.workerName(),
                first.crewId(), first.crewName(), day.size(),
                List.copyOf(houses), List.copyOf(externalIds),
                first.checkedInAt(), anyOpen ? null : lastOut,
                minutes, missing, inProgress));
        }
        rows.sort(Comparator.comparing(DailyRow::date)
            .thenComparing(DailyRow::workerName, Comparator.nullsLast(String::compareTo))
            .thenComparingInt(DailyRow::workerId));
        return rows;
    }

    static BigDecimal hours(long minutes) {
        return BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
    }
}
