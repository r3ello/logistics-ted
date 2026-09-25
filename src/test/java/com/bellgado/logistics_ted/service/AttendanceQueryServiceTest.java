package com.bellgado.logistics_ted.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.bellgado.logistics_ted.domain.Crew;
import com.bellgado.logistics_ted.domain.House;
import com.bellgado.logistics_ted.domain.WorkSession;
import com.bellgado.logistics_ted.domain.Worker;
import com.bellgado.logistics_ted.domain.WorkerRole;
import com.bellgado.logistics_ted.repository.WorkSessionRepository;
import com.bellgado.logistics_ted.repository.WorkerRepository;
import com.bellgado.logistics_ted.service.AttendanceQueryService.SessionView;
import com.bellgado.logistics_ted.service.AttendanceQueryService.State;
import com.bellgado.logistics_ted.service.AttendanceQueryService.WorkerRef;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pure unit test — no Spring context, no Postgres. Pins how report minutes are counted: closed
 * sessions in→out, open-today until now, open-from-a-past-day 0 (flagged), and the Sofia work day.
 */
@ExtendWith(MockitoExtension.class)
class AttendanceQueryServiceTest {

    private static final ZoneId SOFIA = ZoneId.of("Europe/Sofia");
    /** 2026-09-24 14:00 in Sofia (UTC+3 in summer time). */
    private static final Instant NOW = Instant.parse("2026-09-24T11:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 24);

    @Mock WorkSessionRepository sessions;
    @Mock WorkerRepository workers;

    AttendanceQueryService service;

    Crew crewA;
    Worker ivan;
    Worker georgi;
    Worker petar;
    House house12;
    House house3;

    @BeforeEach
    void setUp() {
        service = new AttendanceQueryService(sessions, workers, SOFIA, Clock.fixed(NOW, SOFIA));
        crewA = crew(1, "Crew A");
        Crew crewB = crew(2, "Crew B");
        ivan = worker(10, "Ivan", crewA);
        georgi = worker(11, "Georgi", crewA);
        petar = worker(12, "Petar", crewB);
        house12 = house(12, "Sunny");
        house3 = house(3, "Oak");
    }

    @Test
    void todayIsTheSofiaWorkDay() {
        // 22:30 UTC on the 23rd is already 01:30 on the 24th in Sofia.
        AttendanceQueryService lateNight = new AttendanceQueryService(sessions, workers, SOFIA,
                Clock.fixed(Instant.parse("2026-09-23T22:30:00Z"), ZoneId.of("UTC")));
        assertThat(lateNight.today()).isEqualTo(TODAY);
    }

    @Test
    void countsClosedInProgressAndMissingCheckoutDifferently() {
        WorkSession closed = session(1, ivan, house12, TODAY.minusDays(1),
                "2026-09-23T04:30:00Z", "2026-09-23T13:05:00Z");          // 8h 35m
        WorkSession live = session(2, georgi, house12, TODAY, "2026-09-24T05:00:00Z", null); // 6h so far
        WorkSession stale = session(3, petar, house3, TODAY.minusDays(1), "2026-09-23T05:00:00Z", null);
        when(sessions.findAllInRange(TODAY.minusDays(1), TODAY)).thenReturn(List.of(closed, live, stale));

        List<SessionView> out = service.sessionsBetween(TODAY.minusDays(1), TODAY, null, null, null);

        assertThat(out).extracting(SessionView::state)
                .containsExactly(State.CLOSED, State.IN_PROGRESS, State.MISSING_CHECKOUT);
        assertThat(out).extracting(SessionView::countedMinutes).containsExactly(515L, 360L, 0L);
        // Times are rendered in Sofia local time.
        assertThat(out.get(0).checkedInAt().getHour()).isEqualTo(7);
        assertThat(out.get(0).crewName()).isEqualTo("Crew A");
    }

    @Test
    void filtersByWorkerHouseAndCrew() {
        WorkSession a = session(1, ivan, house12, TODAY, "2026-09-24T05:00:00Z", "2026-09-24T06:00:00Z");
        WorkSession b = session(2, georgi, house3, TODAY, "2026-09-24T05:00:00Z", "2026-09-24T06:00:00Z");
        WorkSession c = session(3, petar, house12, TODAY, "2026-09-24T05:00:00Z", "2026-09-24T06:00:00Z");
        when(sessions.findAllInRange(TODAY, TODAY)).thenReturn(List.of(a, b, c));

        assertThat(service.sessionsBetween(TODAY, TODAY, 11, null, null))
                .extracting(SessionView::sessionId).containsExactly(2);
        assertThat(service.sessionsBetween(TODAY, TODAY, null, 12, null))
                .extracting(SessionView::sessionId).containsExactly(1, 3);
        assertThat(service.sessionsBetween(TODAY, TODAY, null, null, 1))
                .extracting(SessionView::sessionId).containsExactly(1, 2);
        assertThat(service.sessionsBetween(TODAY, TODAY, null, 12, 1))
                .extracting(SessionView::sessionId).containsExactly(1);
    }

    @Test
    void rejectsInvertedAndOversizedRanges() {
        assertThatThrownBy(() -> service.sessionsBetween(TODAY, TODAY.minusDays(1), null, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("before");
        assertThatThrownBy(() -> service.sessionsBetween(TODAY.minusDays(400), TODAY, null, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("too large");
    }

    @Test
    void absentWorkersAreCrewMembersWithNoSessionThatDay() {
        Worker noCrew = worker(13, "Freelancer", null);
        when(workers.findAllWithCrew()).thenReturn(List.of(ivan, georgi, petar, noCrew));
        when(sessions.findAllInRange(TODAY, TODAY)).thenReturn(List.of(
                session(1, georgi, house12, TODAY, "2026-09-24T05:00:00Z", null)));

        List<WorkerRef> absent = service.absentWorkers(TODAY, null);

        // Georgi checked in; the crew-less worker is never expected on site.
        assertThat(absent).extracting(WorkerRef::name).containsExactly("Ivan", "Petar");
    }

    @Test
    void absentWorkersCanBeLimitedToOneCrew() {
        when(workers.findByCrewId(1)).thenReturn(List.of(ivan, georgi));
        when(sessions.findAllInRange(TODAY, TODAY)).thenReturn(List.of());

        assertThat(service.absentWorkers(TODAY, 1)).extracting(WorkerRef::id).containsExactly(11, 10);
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private static Crew crew(int id, String name) {
        Crew c = new Crew();
        c.setId(id);
        c.setName(name);
        return c;
    }

    private static Worker worker(int id, String name, Crew crew) {
        Worker w = new Worker();
        w.setId(id);
        w.setName(name);
        w.setRole(WorkerRole.CREW_MEMBER);
        w.setCrew(crew);
        return w;
    }

    private static House house(int id, String name) {
        House h = new House();
        h.setId(id);
        h.setName(name);
        return h;
    }

    private static WorkSession session(int id, Worker w, House h, LocalDate date, String in, String out) {
        WorkSession s = new WorkSession();
        s.setId(id);
        s.setWorker(w);
        s.setHouse(h);
        s.setSessionDate(date);
        s.setCheckedInAt(OffsetDateTime.parse(in));
        s.setCheckedOutAt(out != null ? OffsetDateTime.parse(out) : null);
        s.setDeviceId("dev-" + id);
        return s;
    }
}
