package com.bellgado.logistics_ted.web.exporter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bellgado.logistics_ted.service.exporter.ExportColumn;
import com.bellgado.logistics_ted.service.exporter.ExportColumnType;
import com.bellgado.logistics_ted.service.exporter.ExportException;
import com.bellgado.logistics_ted.web.exporter.CsvExportWriter.TimeFormat;
import com.bellgado.logistics_ted.web.importer.csv.CsvFormat;
import com.bellgado.logistics_ted.web.importer.csv.CsvReader;
import com.bellgado.logistics_ted.web.importer.csv.CsvTable;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class CsvExportWriterTest {

    private static final CsvFormat COMMA = CsvFormat.defaults();
    private static final CsvFormat BG = new CsvFormat(';', true);

    record Row(String name, BigDecimal hours, ZonedDateTime at, List<String> houses) {}

    private static final List<ExportColumn<Row>> COLS = List.of(
        ExportColumn.of("name", ExportColumnType.TEXT, "", Row::name),
        ExportColumn.of("hours", ExportColumnType.DECIMAL, "", Row::hours),
        ExportColumn.of("at", ExportColumnType.TIMESTAMP, "", Row::at),
        ExportColumn.of("houses", ExportColumnType.PIPE_SET, "", Row::houses));

    private static final ZonedDateTime AT =
        ZonedDateTime.of(2026, 9, 24, 7, 30, 15, 999_000_000, ZoneId.of("Europe/Sofia"));

    private static String csv(List<Row> rows, CsvFormat f, TimeFormat t) {
        return new String(CsvExportWriter.write(COLS, rows, f, t), StandardCharsets.UTF_8);
    }

    @Test
    void writesBomHeaderAndCrlfRows() {
        String out = csv(List.of(new Row("Иван", new BigDecimal("8.50"), AT, List.of("Oak", "Sunny"))),
            COMMA, TimeFormat.ISO);
        assertThat(out).isEqualTo("﻿name,hours,at,houses\r\n"
            + "Иван,8.50,2026-09-24T07:30:15+03:00,Oak|Sunny\r\n");
    }

    @Test
    void honoursDecimalCommaAndLocalTimestamps() {
        String out = csv(List.of(new Row("A", new BigDecimal("8.50"), AT, List.of())), BG, TimeFormat.LOCAL);
        assertThat(out).endsWith("A;8,50;2026-09-24 07:30:15;\r\n");
    }

    @Test
    void nullsAreEmptyCells() {
        assertThat(csv(List.of(new Row(null, null, null, null)), COMMA, TimeFormat.ISO))
            .endsWith("\r\n,,,\r\n");
    }

    @Test
    void quotesDelimitersQuotesAndNewlines() {
        String out = csv(List.of(new Row("Smith, \"Jr\"\nline2", null, null, null)), COMMA, TimeFormat.ISO);
        assertThat(out).endsWith("\"Smith, \"\"Jr\"\"\nline2\",,,\r\n");
    }

    @Test
    void guardsFormulaInjectionInTextButNotInNumbers() {
        assertThat(CsvExportWriter.cell("=HYPERLINK(\"x\")", ExportColumnType.TEXT, COMMA, TimeFormat.ISO))
            .isEqualTo("'=HYPERLINK(\"x\")");
        assertThat(CsvExportWriter.cell("@SUM(A1)", ExportColumnType.TEXT, COMMA, TimeFormat.ISO)).startsWith("'");
        assertThat(CsvExportWriter.cell(List.of("-x"), ExportColumnType.PIPE_SET, COMMA, TimeFormat.ISO))
            .isEqualTo("'-x");
        assertThat(CsvExportWriter.cell(-5, ExportColumnType.INTEGER, COMMA, TimeFormat.ISO)).isEqualTo("-5");
        assertThat(CsvExportWriter.cell(new BigDecimal("-1.25"), ExportColumnType.DECIMAL, COMMA, TimeFormat.ISO))
            .isEqualTo("-1.25");
    }

    @Test
    void outputRoundTripsThroughTheImportReader() {
        byte[] bytes = CsvExportWriter.write(COLS,
            List.of(new Row("Петров; \"Bai\"", new BigDecimal("1.5"), AT, List.of("A"))), BG, TimeFormat.ISO);
        CsvTable table = CsvReader.read(bytes, BG);
        assertThat(table.headers()).containsExactly("name", "hours", "at", "houses");
        assertThat(table.rows()).hasSize(1);
        assertThat(table.rows().get(0).raw("name")).isEqualTo("Петров; \"Bai\"");
    }

    @Test
    void rejectsUnknownTimeFormat() {
        assertThat(TimeFormat.of(null)).isEqualTo(TimeFormat.ISO);
        assertThat(TimeFormat.of("LOCAL")).isEqualTo(TimeFormat.LOCAL);
        assertThatThrownBy(() -> TimeFormat.of("excel")).isInstanceOf(ExportException.class);
    }
}
