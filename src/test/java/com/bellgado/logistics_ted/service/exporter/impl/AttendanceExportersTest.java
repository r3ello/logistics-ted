package com.bellgado.logistics_ted.service.exporter.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bellgado.logistics_ted.domain.House;
import com.bellgado.logistics_ted.repository.HouseRepository;
import com.bellgado.logistics_ted.service.AttendanceQueryService;
import com.bellgado.logistics_ted.service.AttendanceQueryService.SessionView;
import com.bellgado.logistics_ted.service.AttendanceQueryService.State;
import com.bellgado.logistics_ted.service.exporter.ExportColumn;
import com.bellgado.logistics_ted.service.exporter.ExportException;
import com.bellgado.logistics_ted.service.exporter.ExportFilter;
import com.bellgado.logistics_ted.service.exporter.ExportQuery;
import com.bellgado.logistics_ted.service.exporter.impl.AttendanceDailyExporter.DailyRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** The attendance datasets without Spring or Postgres. */
class AttendanceExportersTest {

    private static final ZoneId SOFIA = ZoneId.of("Europe/Sofia");
    private static final LocalDate D1 = LocalDate.of(2026, 9, 23);
    private static final LocalDate D2 = LocalDate.of(2026, 9, 24);

    private static SessionView sv(int id, int workerId, String worker, int houseId, String house, String extId,
                                  LocalDate date, String in, String out, State state, long minutes) {
        return new SessionView(id, workerId, worker, 1, "Crew A", houseId, house, extId, date,
            ZonedDateTime.of(date, LocalTime.parse(in), SOFIA),
            out != null ? ZonedDateTime.of(date, LocalTime.parse(out), SOFIA) : null,
            state, minutes);
    }

    @Test
    void groupsByDayAndWorkerSummingMinutesAndListingHousesInOrder() {
        List<DailyRow> rows = AttendanceDailyExporter.aggregate(List.of(
            sv(1, 10, "Ivan", 5, "Oak", "H-5", D1, "13:00", "17:00", State.CLOSED, 240),
            sv(2, 10, "Ivan", 3, "Sunny", null, D1, "07:30", "12:00", State.CLOSED, 270),
            sv(3, 11, "Georgi", 3, "Sunny", null, D1, "08:00", "16:00", State.CLOSED, 480)));

        assertThat(rows).extracting(DailyRow::workerName).containsExactly("Georgi", "Ivan");
        DailyRow ivan = rows.get(1);
        assertThat(ivan.sessions()).isEqualTo(2);
        assertThat(ivan.countedMinutes()).isEqualTo(510);
        assertThat(ivan.houseNames()).containsExactly("Sunny", "Oak");
        assertThat(ivan.houseExternalIds()).containsExactly("H-5");
        assertThat(ivan.firstCheckIn().toLocalTime()).isEqualTo(LocalTime.of(7, 30));
        assertThat(ivan.lastCheckOut().toLocalTime()).isEqualTo(LocalTime.of(17, 0));
        assertThat(ivan.missingCheckout()).isFalse();
    }

    @Test
    void anOpenSessionBlanksTheLastCheckOutAndSetsTheFlags() {
        List<DailyRow> rows = AttendanceDailyExporter.aggregate(List.of(
            sv(1, 10, "Ivan", 3, "Sunny", null, D1, "07:30", null, State.MISSING_CHECKOUT, 0),
            sv(2, 10, "Ivan", 3, "Sunny", null, D2, "07:30", "10:00", State.CLOSED, 150),
            sv(3, 10, "Ivan", 3, "Sunny", null, D2, "11:00", null, State.IN_PROGRESS, 60)));

        assertThat(rows).extracting(DailyRow::date).containsExactly(D1, D2);
        assertThat(rows.get(0).missingCheckout()).isTrue();
        assertThat(rows.get(0).countedMinutes()).isZero();
        assertThat(rows.get(0).lastCheckOut()).isNull();
        assertThat(rows.get(1).inProgress()).isTrue();
        assertThat(rows.get(1).lastCheckOut()).isNull();
        assertThat(rows.get(1).countedMinutes()).isEqualTo(210);
    }

    @Test
    void dailyMinutesReconcileWithTheSessions() {
        List<SessionView> sessions = List.of(
            sv(1, 10, "Ivan", 3, "Sunny", null, D1, "07:30", "12:00", State.CLOSED, 270),
            sv(2, 11, "Georgi", 3, "Sunny", null, D1, "08:00", "16:00", State.CLOSED, 480),
            sv(3, 10, "Ivan", 3, "Sunny", null, D2, "07:00", null, State.MISSING_CHECKOUT, 0));
        long daily = AttendanceDailyExporter.aggregate(sessions).stream().mapToLong(DailyRow::countedMinutes).sum();
        assertThat(daily).isEqualTo(sessions.stream().mapToLong(SessionView::countedMinutes).sum());
    }

    @Test
    void hoursAreRoundedToTwoDecimals() {
        assertThat(AttendanceDailyExporter.hours(515)).isEqualByComparingTo(new BigDecimal("8.58"));
        assertThat(AttendanceDailyExporter.hours(0)).isEqualByComparingTo("0.00");
    }

    // ── filters ────────────────────────────────────────────────────────────────

    private static ExportQuery query(Map<String, String> raw) {
        return ExportQuery.parse(AttendanceSessionExporter.FILTERS, raw);
    }

    @Test
    void houseExternalIdIsResolvedToTheHouse() {
        AttendanceQueryService attendance = mock(AttendanceQueryService.class);
        HouseRepository houses = mock(HouseRepository.class);
        House h = new House();
        h.setId(12);
        when(houses.findByExternalId("CRM-77")).thenReturn(Optional.of(h));

        new AttendanceSessionExporter(attendance, houses)
            .fetch(query(Map.of("from", "2026-09-01", "to", "2026-09-30", "houseExternalId", " CRM-77 ")));

        verify(attendance).sessionsBetween(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), null, 12, null);
    }

    @Test
    void anUnknownHouseExternalIdIsAnErrorNotAnEmptyFile() {
        AttendanceQueryService attendance = mock(AttendanceQueryService.class);
        HouseRepository houses = mock(HouseRepository.class);
        when(houses.findByExternalId("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> new AttendanceDailyExporter(attendance, houses)
                .fetch(query(Map.of("from", "2026-09-01", "to", "2026-09-30", "houseExternalId", "nope"))))
            .isInstanceOf(ExportException.class)
            .hasMessageContaining("nope");
        verifyNoInteractions(attendance);
    }

    @Test
    void withoutAHouseFilterEveryHouseIsIncluded() {
        AttendanceQueryService attendance = mock(AttendanceQueryService.class);
        HouseRepository houses = mock(HouseRepository.class);
        new AttendanceSessionExporter(attendance, houses).fetch(query(Map.of("from", "2026-09-01", "to", "2026-09-01")));
        verify(attendance).sessionsBetween(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 1), null, null, null);
        verifyNoInteractions(houses);
    }

    @Test
    void internalIdsAreNotFilters() {
        assertThat(AttendanceSessionExporter.FILTERS).extracting(ExportFilter::name)
            .containsExactly("from", "to", "houseExternalId");
    }

    @Test
    void neverExposesDeviceOrCoordinates() {
        var session = new AttendanceSessionExporter(null, null);
        var daily = new AttendanceDailyExporter(null, null);
        List<String> names = Stream.concat(
                session.columns().stream().map(ExportColumn::name),
                daily.columns().stream().map(ExportColumn::name))
            .toList();
        assertThat(names).noneMatch(n -> n.contains("device") || n.contains("lat") || n.contains("lng"));
    }

    @Test
    void columnNamesAreUniquePerDataset() {
        for (var cols : List.of(new AttendanceSessionExporter(null, null).columns(),
                                new AttendanceDailyExporter(null, null).columns())) {
            List<String> names = cols.stream().map(c -> c.name()).toList();
            assertThat(names).doesNotHaveDuplicates();
        }
    }
}
