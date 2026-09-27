package com.bellgado.logistics_ted.service.exporter.impl;

import static com.bellgado.logistics_ted.service.exporter.ExportColumnType.DATE;
import static com.bellgado.logistics_ted.service.exporter.ExportColumnType.DECIMAL;
import static com.bellgado.logistics_ted.service.exporter.ExportColumnType.ENUM;
import static com.bellgado.logistics_ted.service.exporter.ExportColumnType.INTEGER;
import static com.bellgado.logistics_ted.service.exporter.ExportColumnType.TEXT;
import static com.bellgado.logistics_ted.service.exporter.ExportColumnType.TIMESTAMP;

import com.bellgado.logistics_ted.service.AttendanceQueryService;
import com.bellgado.logistics_ted.service.AttendanceQueryService.SessionView;
import com.bellgado.logistics_ted.service.exporter.EntityExporter;
import com.bellgado.logistics_ted.service.exporter.ExportColumn;
import com.bellgado.logistics_ted.service.exporter.ExportException;
import com.bellgado.logistics_ted.service.exporter.ExportFilter;
import com.bellgado.logistics_ted.service.exporter.ExportQuery;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The QR check-in log, one row per check-in.
 *
 * <p>Rows come from {@link AttendanceQueryService#sessionsBetween}, never from {@code work_session}
 * directly: that service is the one place the counting rule lives (closed = in→out, open today =
 * in→now, open from a past day = 0 and flagged), so this CSV, the Telegram bot and any future report
 * always agree on the hours.
 *
 * <p>The device fingerprint and the check-in/out GPS coordinates are deliberately not columns —
 * they are personal data with no payroll use, and a column that does not exist cannot be requested.
 */
@Component
public class AttendanceSessionExporter implements EntityExporter<SessionView> {

    static final List<ExportFilter> FILTERS = List.of(
        ExportFilter.requiredDate("from", "First work day, inclusive (YYYY-MM-DD)."),
        ExportFilter.requiredDate("to", "Last work day, inclusive. At most 366 days after `from`."),
        ExportFilter.optionalInteger("crewId", "Only workers currently in this crew."),
        ExportFilter.optionalInteger("workerId", "Only this worker."),
        ExportFilter.optionalInteger("houseId", "Only check-ins at this house."));

    private static final List<ExportColumn<SessionView>> COLUMNS = List.of(
        ExportColumn.of("session_id", INTEGER, "Internal id of the check-in.", SessionView::sessionId),
        ExportColumn.of("date", DATE, "Work day (Europe/Sofia).", SessionView::date),
        ExportColumn.of("worker_id", INTEGER, "Internal worker id.", SessionView::workerId),
        ExportColumn.of("worker_name", TEXT, "Worker's name.", SessionView::workerName),
        ExportColumn.optional("crew_id", INTEGER, "Internal id of the worker's current crew.", SessionView::crewId),
        ExportColumn.of("crew_name", TEXT, "Worker's current crew.", SessionView::crewName),
        ExportColumn.optional("house_id", INTEGER, "Internal house id.", SessionView::houseId),
        ExportColumn.of("house_external_id", TEXT, "The house's External ID — the key used by the CSV import.",
            SessionView::houseExternalId),
        ExportColumn.of("house_name", TEXT, "House name.", SessionView::houseName),
        ExportColumn.of("checked_in_at", TIMESTAMP, "Check-in time.", SessionView::checkedInAt),
        ExportColumn.of("checked_out_at", TIMESTAMP, "Check-out time; empty while the session is open.",
            SessionView::checkedOutAt),
        ExportColumn.of("state", ENUM, "CLOSED, IN_PROGRESS (open, today) or MISSING_CHECKOUT (open, past day).",
            SessionView::state),
        ExportColumn.of("counted_minutes", INTEGER, "Minutes counted. MISSING_CHECKOUT counts 0.",
            SessionView::countedMinutes),
        ExportColumn.of("counted_hours", DECIMAL, "counted_minutes / 60, two decimals.",
            s -> AttendanceDailyExporter.hours(s.countedMinutes())));

    private final AttendanceQueryService attendance;

    public AttendanceSessionExporter(AttendanceQueryService attendance) {
        this.attendance = attendance;
    }

    @Override
    public String name() {
        return "attendance-sessions";
    }

    @Override
    public String description() {
        return "The QR check-in log: one row per check-in, with the minutes it counts for.";
    }

    @Override
    public List<ExportColumn<SessionView>> columns() {
        return COLUMNS;
    }

    @Override
    public List<ExportFilter> filters() {
        return FILTERS;
    }

    @Override
    public List<SessionView> fetch(ExportQuery q) {
        return sessions(attendance, q);
    }

    @Override
    public String fileName(ExportQuery q) {
        return name() + "_" + q.date("from") + "_" + q.date("to") + ".csv";
    }

    /** Shared with the daily exporter, which aggregates the very same sessions. */
    static List<SessionView> sessions(AttendanceQueryService attendance, ExportQuery q) {
        try {
            return attendance.sessionsBetween(q.date("from"), q.date("to"),
                q.integer("workerId"), q.integer("houseId"), q.integer("crewId"));
        } catch (IllegalArgumentException e) {
            // Range backwards or too wide — the service's message is already user-facing.
            throw new ExportException(e.getMessage());
        }
    }
}
