package com.bellgado.logistics_ted.web.exporter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.bellgado.logistics_ted.service.AuditLogService;
import com.bellgado.logistics_ted.service.exporter.EntityExporter;
import com.bellgado.logistics_ted.service.exporter.ExportColumn;
import com.bellgado.logistics_ted.service.exporter.ExportColumnType;
import com.bellgado.logistics_ted.service.exporter.ExportFilter;
import com.bellgado.logistics_ted.service.exporter.ExportQuery;
import com.bellgado.logistics_ted.service.exporter.ExporterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;

/** Controller logic without Spring or Postgres: a stub dataset, a mocked audit log. */
class ExportControllerTest {

    record Person(int id, String name, LocalDate day) {}

    /** Records the query it was given so tests can assert the parsed filters. */
    static final class StubExporter implements EntityExporter<Person> {
        ExportQuery lastQuery;

        @Override public String name() { return "people"; }
        @Override public String description() { return "Stub."; }
        @Override public List<ExportColumn<Person>> columns() {
            return List.of(
                ExportColumn.of("id", ExportColumnType.INTEGER, "", Person::id),
                ExportColumn.of("name", ExportColumnType.TEXT, "", Person::name),
                ExportColumn.optional("day", ExportColumnType.DATE, "", Person::day));
        }
        @Override public List<ExportFilter> filters() {
            return List.of(ExportFilter.requiredDate("from", ""), ExportFilter.optionalInteger("crewId", ""));
        }
        @Override public List<Person> fetch(ExportQuery q) {
            lastQuery = q;
            return List.of(new Person(1, "Ivan", LocalDate.of(2026, 9, 1)), new Person(2, "Maria", null));
        }
    }

    private StubExporter stub;
    private AuditLogService audit;
    private ExportController controller;

    @BeforeEach
    void setUp() {
        stub = new StubExporter();
        audit = mock(AuditLogService.class);
        controller = new ExportController(new ExporterRegistry(List.of(stub)), audit);
    }

    private static String body(ResponseEntity<?> r) {
        return new String((byte[]) r.getBody(), StandardCharsets.UTF_8);
    }

    @Test
    void defaultColumnsWhenNoneRequested() {
        ResponseEntity<?> r = controller.export("people", Map.of("from", "2026-09-01"));
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(body(r)).isEqualTo("﻿id,name\r\n1,Ivan\r\n2,Maria\r\n");
        assertThat(r.getHeaders().getFirst("X-Export-Rows")).isEqualTo("2");
        assertThat(r.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).contains("people.csv");
    }

    @Test
    void requestedColumnsInRequestedOrderIncludingOptionalOnes() {
        ResponseEntity<?> r = controller.export("people",
            Map.of("from", "2026-09-01", "columns", "day, NAME,day"));
        assertThat(body(r)).isEqualTo("﻿day,name\r\n2026-09-01,Ivan\r\n,Maria\r\n");
    }

    @Test
    void parsesDeclaredFilters() {
        controller.export("people", Map.of("from", "2026-09-01", "crewId", "7"));
        assertThat(stub.lastQuery.date("from")).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(stub.lastQuery.integer("crewId")).isEqualTo(7);
    }

    @Test
    void unknownColumnIs400() {
        ResponseEntity<?> r = controller.export("people", Map.of("from", "2026-09-01", "columns", "id,salary"));
        assertThat(r.getStatusCode().value()).isEqualTo(400);
        assertThat(r.getBody().toString()).contains("salary");
    }

    @Test
    void misspeltFilterIs400NotASilentlyWiderExport() {
        ResponseEntity<?> r = controller.export("people", Map.of("from", "2026-09-01", "crew_id", "7"));
        assertThat(r.getStatusCode().value()).isEqualTo(400);
        assertThat(r.getBody().toString()).contains("crew_id");
        assertThat(stub.lastQuery).isNull();
    }

    @Test
    void missingRequiredFilterIs400() {
        assertThat(controller.export("people", Map.of()).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void badFilterValueIs400() {
        assertThat(controller.export("people", Map.of("from", "01.09.2026")).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.export("people", Map.of("from", "2026-09-01", "crewId", "x")).getStatusCode().value())
            .isEqualTo(400);
    }

    @Test
    void decimalCommaNeedsANonCommaDelimiter() {
        assertThat(controller.export("people", Map.of("from", "2026-09-01", "decimal", "comma"))
            .getStatusCode().value()).isEqualTo(400);
        assertThat(controller.export("people",
            Map.of("from", "2026-09-01", "decimal", "comma", "delimiter", "semicolon"))
            .getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void unknownDatasetIs404() {
        assertThat(controller.export("salaries", Map.of()).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @SuppressWarnings("unchecked")
    void auditsSuccessfulExportsWithFiltersColumnsAndRowCountButNoRows() {
        controller.export("people", Map.of("from", "2026-09-01", "columns", "name"));
        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(audit).recordExport(any(), eq("/api/export/people"), eq("people"), details.capture(), any(), any());
        assertThat(details.getValue())
            .containsEntry("filters", Map.of("from", "2026-09-01"))
            .containsEntry("columns", List.of("name"))
            .containsEntry("rows", 2);
        assertThat(details.getValue().toString()).doesNotContain("Ivan");
    }

    @Test
    void failedRequestsAreNotAudited() {
        controller.export("people", Map.of("from", "2026-09-01", "columns", "nope"));
        verify(audit, never()).recordExport(any(), any(), any(), anyMap(), any(), any());
    }

    @Test
    void anAuditFailureDoesNotBreakTheExport() {
        doThrow(new RuntimeException("db down")).when(audit)
            .recordExport(any(), any(), any(), anyMap(), any(), any());
        assertThat(controller.export("people", Map.of("from", "2026-09-01")).getStatusCode().value())
            .isEqualTo(200);
    }

    @Test
    void catalogueDescribesFiltersAndColumns() {
        List<Map<String, Object>> cat = controller.entities();
        assertThat(cat).hasSize(1);
        assertThat(cat.get(0)).containsEntry("name", "people");
        assertThat(cat.get(0).get("columns").toString()).contains("name=day", "default=false", "type=date");
        assertThat(cat.get(0).get("filters").toString()).contains("name=from", "required=true");
    }

    @Test
    void isOpenToAdminsAndExportersOnly() {
        PreAuthorize a = ExportController.class.getAnnotation(PreAuthorize.class);
        assertThat(a).isNotNull();
        assertThat(a.value().replace(" ", "")).isEqualTo("hasAnyRole('ADMIN','EXPORTER')");
    }
}
