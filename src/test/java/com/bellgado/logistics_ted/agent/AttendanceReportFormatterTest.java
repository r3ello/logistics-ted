package com.bellgado.logistics_ted.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bellgado.logistics_ted.agent.AttendanceReportFormatter.GroupBy;
import com.bellgado.logistics_ted.service.AttendanceQueryService.SessionView;
import com.bellgado.logistics_ted.service.AttendanceQueryService.State;
import com.bellgado.logistics_ted.service.AttendanceQueryService.WorkerRef;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Pure unit test for the Telegram attendance text + the tools' argument parsing. */
class AttendanceReportFormatterTest {

    private static final ZoneId SOFIA = ZoneId.of("Europe/Sofia");
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 24);

    private static SessionView sv(int id, int workerId, String worker, String crew, int houseId, String house,
                                  LocalDate date, String in, String out, State state, long minutes) {
        return new SessionView(id, workerId, worker, crew != null ? 1 : null, crew, houseId, house, date,
                ZonedDateTime.of(date, java.time.LocalTime.parse(in), SOFIA),
                out != null ? ZonedDateTime.of(date, java.time.LocalTime.parse(out), SOFIA) : null,
                state, minutes);
    }

    @Test
    void dailyListsEachSessionAndFlagsMissingCheckout() {
        String text = AttendanceReportFormatter.daily(TODAY, true, List.of(
                sv(1, 10, "Ivan", "Crew A", 12, "Sunny", TODAY, "07:30", "16:05", State.CLOSED, 515),
                sv(2, 11, "Georgi", "Crew A", 12, "Sunny", TODAY, "08:00", null, State.IN_PROGRESS, 360),
                sv(3, 12, "Petar", null, 3, "Oak", TODAY, "07:50", null, State.MISSING_CHECKOUT, 0)),
                "Europe/Sofia");

        assertThat(text)
                .startsWith("Attendance for 2026-09-24 Thu (times in Europe/Sofia): 3 workers checked in, 1 still on site, total 14h 35m.")
                .contains("• Ivan (Crew A) — Sunny (house 12): 07:30 → 16:05 (8h 35m)")
                .contains("• Georgi (Crew A) — Sunny (house 12): 08:00 → still on site (6h 0m so far)")
                .contains("• Petar — Oak (house 3): 07:50 → no check-out recorded (not counted)")
                .contains("1 session has no check-out recorded");
    }

    @Test
    void dailyWithNoSessions() {
        assertThat(AttendanceReportFormatter.daily(TODAY, true, List.of(), "Europe/Sofia"))
                .isEqualTo("No check-ins recorded on 2026-09-24 Thu.");
    }

    @Test
    void workerHoursSumsPerDayAndPerHouse() {
        LocalDate d1 = TODAY.minusDays(1);
        String text = AttendanceReportFormatter.workerHours(new WorkerRef(10, "Ivan", "CREW_MEMBER", 1, "Crew A"),
                d1, TODAY, List.of(
                        sv(1, 10, "Ivan", "Crew A", 12, "Sunny", d1, "07:00", "12:00", State.CLOSED, 300),
                        sv(2, 10, "Ivan", "Crew A", 3, "Oak", d1, "13:00", "16:00", State.CLOSED, 180),
                        sv(3, 10, "Ivan", "Crew A", 12, "Sunny", TODAY, "07:00", null, State.IN_PROGRESS, 420)));

        assertThat(text)
                .startsWith("Ivan (Crew A) — 2026-09-23 Wed to 2026-09-24 Thu")
                .contains("Total: 15h 0m over 2 days (3 sessions).")
                .contains("• 2026-09-23 Wed — 8h 0m (Sunny 07:00–12:00; Oak 13:00–16:00)")
                .contains("• 2026-09-24 Thu — 7h 0m (Sunny 07:00–now)")
                .contains("• Sunny (house 12) — 12h 0m")
                .contains("in progress today");
    }

    @Test
    void summaryRanksGroupsByHours() {
        String text = AttendanceReportFormatter.summary(TODAY.minusDays(6), TODAY, GroupBy.CREW, "", List.of(
                sv(1, 10, "Ivan", "Crew A", 12, "Sunny", TODAY, "07:00", "09:00", State.CLOSED, 120),
                sv(2, 12, "Petar", null, 3, "Oak", TODAY, "07:00", "15:00", State.CLOSED, 480),
                sv(3, 11, "Georgi", "Crew A", 12, "Sunny", TODAY, "07:00", "08:00", State.CLOSED, 60)));

        assertThat(text)
                .contains("1. No crew — 8h 0m · 1 day · 1 worker")
                .contains("2. Crew A (crew 1) — 3h 0m · 1 day · 2 workers")
                .contains("Total: 11h 0m across 3 workers.");
    }

    @Test
    void absentGroupsByCrewAndMarksLeaders() {
        String text = AttendanceReportFormatter.absent(TODAY, true, List.of(
                new WorkerRef(10, "Ivan", "CREW_LEADER", 1, "Crew A"),
                new WorkerRef(11, "Georgi", "CREW_MEMBER", 1, "Crew A")));

        assertThat(text)
                .contains("2 crew workers with no check-in so far")
                .contains("• Crew A: Ivan [crew leader], Georgi");
    }

    @Test
    void openSeparatesLiveFromForgotten() {
        String text = AttendanceReportFormatter.open(List.of(
                sv(1, 10, "Ivan", "Crew A", 12, "Sunny", TODAY, "07:30", null, State.IN_PROGRESS, 390),
                sv(2, 12, "Petar", null, 3, "Oak", TODAY.minusDays(3), "07:50", null, State.MISSING_CHECKOUT, 0)));

        assertThat(text)
                .contains("Open sessions (no check-out yet): 2")
                .contains("• Ivan (Crew A) — Sunny: since 07:30 (6h 30m)")
                .contains("• Petar — Oak: 2026-09-21 Mon, checked in 07:50");
    }

    @Test
    void parsesToolArguments() {
        assertThat(LogisticsAgentTools.parseDay("", TODAY, TODAY.minusDays(6))).isEqualTo(TODAY.minusDays(6));
        assertThat(LogisticsAgentTools.parseDay(" Yesterday ", TODAY, TODAY)).isEqualTo(TODAY.minusDays(1));
        assertThat(LogisticsAgentTools.parseDay("2026-09-01", TODAY, TODAY)).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThatThrownBy(() -> LogisticsAgentTools.parseDay("last week", TODAY, TODAY))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("YYYY-MM-DD");

        assertThat(LogisticsAgentTools.parseOptionalId("", "crewId")).isNull();
        assertThat(LogisticsAgentTools.parseOptionalId(" 7 ", "crewId")).isEqualTo(7);
        assertThatThrownBy(() -> LogisticsAgentTools.parseOptionalId("seven", "crewId"))
                .hasMessage("crewId must be a number (got: seven).");

        assertThat(LogisticsAgentTools.parseGroupBy("")).isEqualTo(GroupBy.WORKER);
        assertThat(LogisticsAgentTools.parseGroupBy("Houses")).isEqualTo(GroupBy.HOUSE);
        assertThatThrownBy(() -> LogisticsAgentTools.parseGroupBy("day"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
