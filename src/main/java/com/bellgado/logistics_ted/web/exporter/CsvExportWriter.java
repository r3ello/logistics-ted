package com.bellgado.logistics_ted.web.exporter;

import com.bellgado.logistics_ted.service.exporter.ExportColumn;
import com.bellgado.logistics_ted.service.exporter.ExportColumnType;
import com.bellgado.logistics_ted.service.exporter.ExportException;
import com.bellgado.logistics_ted.web.importer.csv.CsvFormat;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Renders rows into CSV bytes. The mirror image of the import's {@code CsvReader}: RFC 4180
 * quoting, CRLF line endings, UTF-8 with a byte-order mark (so Excel on Windows opens the Cyrillic
 * correctly), and the same explicit delimiter / decimal-separator dialect the import accepts, so an
 * exported file can be fed straight back in.
 *
 * <p><b>Formula injection.</b> A free-text cell starting with {@code = + - @}, tab or CR is prefixed
 * with {@code '}: worker and house names are typed by people, and a name like {@code =HYPERLINK(...)}
 * would otherwise execute when the file is opened in a spreadsheet. The guard is applied to text
 * columns only, so numbers and timestamps are never altered.
 */
public final class CsvExportWriter {

    /** How timestamp columns are written. */
    public enum TimeFormat {
        /** {@code 2026-09-24T07:30:00+03:00} — unambiguous, for machines. */
        ISO,
        /** {@code 2026-09-24 07:30:00} in Europe/Sofia — what a spreadsheet parses as a date-time. */
        LOCAL;

        public static TimeFormat of(String raw) {
            if (raw == null || raw.isBlank() || raw.trim().equalsIgnoreCase("iso")) return ISO;
            if (raw.trim().equalsIgnoreCase("local")) return LOCAL;
            throw new ExportException("timestamps must be 'iso' or 'local', got '" + raw + "'.");
        }
    }

    private static final DateTimeFormatter LOCAL_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String BOM = "﻿";
    private static final String EOL = "\r\n";

    private CsvExportWriter() {}

    public static <R> byte[] write(List<ExportColumn<R>> columns, List<R> rows,
                                   CsvFormat format, TimeFormat time) {
        char d = format.delimiter();
        StringBuilder sb = new StringBuilder(BOM);
        sb.append(columns.stream().map(c -> quote(c.name(), d)).collect(Collectors.joining(String.valueOf(d))))
          .append(EOL);
        for (R row : rows) {
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) sb.append(d);
                ExportColumn<R> c = columns.get(i);
                sb.append(quote(cell(c.value().apply(row), c.type(), format, time), d));
            }
            sb.append(EOL);
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    static String cell(Object v, ExportColumnType type, CsvFormat format, TimeFormat time) {
        if (v == null) return "";
        return switch (type) {
            case INTEGER, DATE -> v.toString();
            case DECIMAL -> {
                String s = (v instanceof BigDecimal b ? b : new BigDecimal(v.toString())).toPlainString();
                yield format.decimalComma() ? s.replace('.', ',') : s;
            }
            case TIMESTAMP -> timestamp(v, time);
            case BOOLEAN -> Boolean.toString((Boolean) v);
            case ENUM -> v instanceof Enum<?> e ? e.name() : v.toString();
            case PIPE_SET -> guard(v instanceof Collection<?> c
                ? c.stream().map(String::valueOf).collect(Collectors.joining("|"))
                : v.toString());
            case TEXT -> guard(v.toString());
        };
    }

    private static String timestamp(Object v, TimeFormat time) {
        OffsetDateTime t = switch (v) {
            case ZonedDateTime z -> z.toOffsetDateTime();
            case OffsetDateTime o -> o;
            default -> throw new IllegalArgumentException("Not a timestamp: " + v.getClass().getName());
        };
        t = t.truncatedTo(ChronoUnit.SECONDS);
        return time == TimeFormat.LOCAL ? LOCAL_FMT.format(t) : DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(t);
    }

    static String guard(String s) {
        if (s.isEmpty()) return s;
        char c = s.charAt(0);
        return (c == '=' || c == '+' || c == '-' || c == '@' || c == '\t' || c == '\r') ? "'" + s : s;
    }

    static String quote(String s, char delimiter) {
        if (s.indexOf(delimiter) < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0 && s.indexOf('\r') < 0) {
            return s;
        }
        return '"' + s.replace("\"", "\"\"") + '"';
    }
}
