package com.bellgado.logistics_ted.agent;

import com.bellgado.logistics_ted.service.AttendanceQueryService.SessionView;
import com.bellgado.logistics_ted.service.AttendanceQueryService.State;
import com.bellgado.logistics_ted.service.AttendanceQueryService.WorkerRef;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Renders attendance query results as the compact plain text the Telegram agent's tools return
 * (the LLM echoes verbose JSON back at users — same reason {@code calculateOrder} returns text).
 * Pure functions over {@link SessionView}s, so it is unit-testable without Spring or Postgres.
 */
final class AttendanceReportFormatter {

    enum GroupBy { WORKER, CREW, HOUSE }

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final String NO_CREW = "No crew";

    private AttendanceReportFormatter() {}

    static String daily(LocalDate date, boolean isToday, List<SessionView> list, String zoneId) {
        if (list.isEmpty()) {
            return "No check-ins recorded on " + day(date) + ".";
        }
        long workers = list.stream().map(SessionView::workerId).distinct().count();
        long onSite = list.stream().filter(s -> s.state() == State.IN_PROGRESS)
                .map(SessionView::workerId).distinct().count();
        long total = list.stream().mapToLong(SessionView::countedMinutes).sum();

        StringBuilder sb = new StringBuilder();
        sb.append("Attendance for ").append(day(date)).append(" (times in ").append(zoneId).append("): ")
          .append(workers).append(workers == 1 ? " worker" : " workers").append(" checked in");
        if (isToday) {
            sb.append(", ").append(onSite).append(" still on site");
        }
        sb.append(", total ").append(duration(total)).append(".\n");

        List<SessionView> sorted = new ArrayList<>(list);
        sorted.sort(Comparator.comparing(SessionView::workerName, Comparator.nullsLast(String::compareTo))
                .thenComparing(SessionView::checkedInAt));
        for (SessionView s : sorted) {
            sb.append("• ").append(who(s)).append(" — ").append(s.houseName()).append(" (house ")
              .append(s.houseId()).append("): ").append(span(s)).append('\n');
        }
        appendMissingNote(sb, list);
        return sb.toString().trim();
    }

    static String workerHours(WorkerRef worker, LocalDate from, LocalDate to, List<SessionView> list) {
        StringBuilder sb = new StringBuilder();
        sb.append(worker.name());
        if (worker.crewName() != null) {
            sb.append(" (").append(worker.crewName()).append(')');
        }
        sb.append(" — ").append(range(from, to)).append('\n');
        if (list.isEmpty()) {
            sb.append("No check-ins recorded in this period.");
            return sb.toString();
        }
        long total = list.stream().mapToLong(SessionView::countedMinutes).sum();
        long days = list.stream().map(SessionView::date).distinct().count();
        sb.append("Total: ").append(duration(total)).append(" over ").append(days)
          .append(days == 1 ? " day" : " days").append(" (").append(list.size())
          .append(list.size() == 1 ? " session" : " sessions").append(").\n");

        sb.append("By day:\n");
        Map<LocalDate, List<SessionView>> byDay = new LinkedHashMap<>();
        list.stream().sorted(Comparator.comparing(SessionView::checkedInAt))
            .forEach(s -> byDay.computeIfAbsent(s.date(), k -> new ArrayList<>()).add(s));
        byDay.forEach((d, sessions) -> {
            long dayTotal = sessions.stream().mapToLong(SessionView::countedMinutes).sum();
            sb.append("• ").append(day(d)).append(" — ").append(duration(dayTotal)).append(" (");
            List<String> parts = new ArrayList<>();
            for (SessionView s : sessions) {
                parts.add(s.houseName() + " " + shortSpan(s));
            }
            sb.append(String.join("; ", parts)).append(")\n");
        });

        Map<String, Long> byHouse = new LinkedHashMap<>();
        for (SessionView s : list) {
            byHouse.merge(s.houseName() + " (house " + s.houseId() + ")", s.countedMinutes(), Long::sum);
        }
        if (byHouse.size() > 1) {
            sb.append("By house:\n");
            byHouse.entrySet().stream()
                   .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                   .forEach(e -> sb.append("• ").append(e.getKey()).append(" — ")
                                   .append(duration(e.getValue())).append('\n'));
        }
        if (list.stream().anyMatch(s -> s.state() == State.IN_PROGRESS)) {
            sb.append("Includes a session still in progress today (counted until now).\n");
        }
        appendMissingNote(sb, list);
        return sb.toString().trim();
    }

    static String summary(LocalDate from, LocalDate to, GroupBy groupBy, String filterLabel,
                          List<SessionView> list) {
        StringBuilder sb = new StringBuilder();
        sb.append("Hours summary ").append(range(from, to)).append(", by ")
          .append(groupBy.name().toLowerCase(Locale.ROOT));
        if (filterLabel != null && !filterLabel.isBlank()) {
            sb.append(" (").append(filterLabel).append(')');
        }
        sb.append(":\n");
        if (list.isEmpty()) {
            sb.append("No check-ins recorded in this period.");
            return sb.toString();
        }

        Map<String, Group> groups = new LinkedHashMap<>();
        for (SessionView s : list) {
            String key = switch (groupBy) {
                case WORKER -> s.workerName() + (s.crewName() != null ? " (" + s.crewName() + ")" : "");
                case CREW -> s.crewName() != null ? s.crewName() + " (crew " + s.crewId() + ")" : NO_CREW;
                case HOUSE -> s.houseName() + " (house " + s.houseId() + ")";
            };
            groups.computeIfAbsent(key, k -> new Group()).add(s);
        }
        List<Map.Entry<String, Group>> ranked = new ArrayList<>(groups.entrySet());
        ranked.sort(Comparator.comparingLong((Map.Entry<String, Group> e) -> e.getValue().minutes).reversed()
                .thenComparing(Map.Entry::getKey));
        int i = 1;
        for (Map.Entry<String, Group> e : ranked) {
            Group g = e.getValue();
            sb.append(i++).append(". ").append(e.getKey()).append(" — ").append(duration(g.minutes))
              .append(" · ").append(g.days.size()).append(g.days.size() == 1 ? " day" : " days");
            if (groupBy != GroupBy.WORKER) {
                sb.append(" · ").append(g.workers.size()).append(g.workers.size() == 1 ? " worker" : " workers");
            }
            sb.append('\n');
        }
        long total = list.stream().mapToLong(SessionView::countedMinutes).sum();
        long workers = list.stream().map(SessionView::workerId).distinct().count();
        sb.append("Total: ").append(duration(total)).append(" across ").append(workers)
          .append(workers == 1 ? " worker" : " workers").append(".\n");
        if (list.stream().anyMatch(s -> s.state() == State.IN_PROGRESS)) {
            sb.append("Includes sessions still in progress today (counted until now).\n");
        }
        appendMissingNote(sb, list);
        return sb.toString().trim();
    }

    static String absent(LocalDate date, boolean isToday, List<WorkerRef> absent) {
        if (absent.isEmpty()) {
            return "Every crew worker checked in on " + day(date) + ".";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(day(date)).append(" — ").append(absent.size())
          .append(absent.size() == 1 ? " crew worker" : " crew workers").append(" with no check-in");
        if (isToday) {
            sb.append(" so far (they may still check in later today)");
        }
        sb.append(":\n");
        Map<String, List<String>> byCrew = new LinkedHashMap<>();
        for (WorkerRef w : absent) {
            String crew = w.crewName() != null ? w.crewName() : NO_CREW;
            byCrew.computeIfAbsent(crew, k -> new ArrayList<>())
                  .add(w.name() + (w.role() != null && !"CREW_MEMBER".equals(w.role())
                          ? " [" + w.role().toLowerCase(Locale.ROOT).replace('_', ' ') + "]" : ""));
        }
        byCrew.forEach((crew, names) ->
                sb.append("• ").append(crew).append(": ").append(String.join(", ", names)).append('\n'));
        return sb.toString().trim();
    }

    static String open(List<SessionView> open) {
        if (open.isEmpty()) {
            return "No open sessions — everyone who checked in has checked out.";
        }
        List<SessionView> live = open.stream().filter(s -> s.state() == State.IN_PROGRESS).toList();
        List<SessionView> stale = open.stream().filter(s -> s.state() == State.MISSING_CHECKOUT).toList();
        StringBuilder sb = new StringBuilder();
        sb.append("Open sessions (no check-out yet): ").append(open.size()).append('\n');
        if (!live.isEmpty()) {
            sb.append("Still on site today:\n");
            for (SessionView s : live) {
                sb.append("• ").append(who(s)).append(" — ").append(s.houseName()).append(": since ")
                  .append(HM.format(s.checkedInAt())).append(" (").append(duration(s.countedMinutes()))
                  .append(")\n");
            }
        }
        if (!stale.isEmpty()) {
            sb.append("Forgot to check out on a past day (counted as 0h until corrected):\n");
            for (SessionView s : stale) {
                sb.append("• ").append(who(s)).append(" — ").append(s.houseName()).append(": ")
                  .append(day(s.date())).append(", checked in ").append(HM.format(s.checkedInAt()))
                  .append('\n');
            }
        }
        return sb.toString().trim();
    }

    static String duration(long minutes) {
        return (minutes / 60) + "h " + (minutes % 60) + "m";
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static void appendMissingNote(StringBuilder sb, List<SessionView> list) {
        long missing = list.stream().filter(s -> s.state() == State.MISSING_CHECKOUT).count();
        if (missing > 0) {
            sb.append("⚠️ ").append(missing).append(missing == 1 ? " session has" : " sessions have")
              .append(" no check-out recorded and ").append(missing == 1 ? "is" : "are")
              .append(" not counted in the hours.\n");
        }
    }

    private static String who(SessionView s) {
        return s.crewName() != null ? s.workerName() + " (" + s.crewName() + ")" : s.workerName();
    }

    private static String span(SessionView s) {
        String in = HM.format(s.checkedInAt());
        return switch (s.state()) {
            case CLOSED -> in + " → " + endTime(s) + " (" + duration(s.countedMinutes()) + ")";
            case IN_PROGRESS -> in + " → still on site (" + duration(s.countedMinutes()) + " so far)";
            case MISSING_CHECKOUT -> in + " → no check-out recorded (not counted)";
        };
    }

    private static String shortSpan(SessionView s) {
        String in = HM.format(s.checkedInAt());
        return switch (s.state()) {
            case CLOSED -> in + "–" + endTime(s);
            case IN_PROGRESS -> in + "–now";
            case MISSING_CHECKOUT -> in + "–? no check-out";
        };
    }

    /** Check-out time, with its date when it fell on a different day than the check-in. */
    private static String endTime(SessionView s) {
        ZonedDateTime out = s.checkedOutAt();
        String t = HM.format(out);
        return Objects.equals(out.toLocalDate(), s.checkedInAt().toLocalDate()) ? t : out.toLocalDate() + " " + t;
    }

    private static String day(LocalDate d) {
        return d + " " + d.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
    }

    private static String range(LocalDate from, LocalDate to) {
        return from.equals(to) ? day(from) : day(from) + " to " + day(to);
    }

    private static final class Group {
        long minutes;
        final Set<LocalDate> days = new LinkedHashSet<>();
        final Set<Integer> workers = new LinkedHashSet<>();

        void add(SessionView s) {
            minutes += s.countedMinutes();
            days.add(s.date());
            workers.add(s.workerId());
        }
    }
}
