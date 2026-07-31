package com.bellgado.logistics_ted.web.importer.csv;

import com.bellgado.logistics_ted.service.importer.ImportErrorCode;
import com.bellgado.logistics_ted.service.importer.ValueNormalizer;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One data row, addressed by column name. Accessors are typed and fail loudly with the line and
 * column attached, so a bad cell becomes a {@code FAILED} row in the report rather than an opaque
 * constraint violation from the database three steps later.
 *
 * <p>Note the distinction the whole merge depends on: {@link #has(String)} answers "does this file
 * manage that field?" (the column is in the header) while a {@code null} from an accessor answers
 * "is the cell empty?". A column absent from the header is excluded from the merge entirely; an
 * empty cell is a value the merge will apply.
 */
public final class CsvRow {

    private final Map<String, Integer> index;
    private final String[] values;
    private final int line;
    private final CsvFormat format;

    CsvRow(Map<String, Integer> index, String[] values, int line, CsvFormat format) {
        this.index = index;
        this.values = values;
        this.line = line;
        this.format = format;
    }

    private static final DateTimeFormatter[] DATE_FORMATS = {
        DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ofPattern("dd.MM.yyyy"),
        DateTimeFormatter.ofPattern("dd/MM/yyyy"),
        DateTimeFormatter.ofPattern("d.M.yyyy"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd")
    };

    /** 1-based line in the source file, for the error report. */
    public int line() {
        return line;
    }

    /** Whether the file declares this column at all (header presence, not cell emptiness). */
    public boolean has(String column) {
        return index.containsKey(key(column));
    }

    /** Raw cell, untouched. Null when the column is absent. */
    public String raw(String column) {
        Integer i = index.get(key(column));
        if (i == null || i >= values.length) return null;
        return values[i];
    }

    /** Trimmed text, or null when absent or blank. */
    public String text(String column) {
        String v = raw(column);
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    public String requiredText(String column) {
        String v = text(column);
        if (v == null) {
            throw fail(column, null, ImportErrorCode.EMPTY_REQUIRED, "Column '" + column + "' is required.");
        }
        return v;
    }

    /** Text with a maximum length, matching the target column's {@code varchar} limit. */
    public String text(String column, int maxLength) {
        String v = text(column);
        if (v != null && v.length() > maxLength) {
            throw fail(column, v, ImportErrorCode.TOO_LONG,
                "Value is " + v.length() + " characters; the limit is " + maxLength + ".");
        }
        return v;
    }

    public BigDecimal decimal(String column) {
        String v = text(column);
        if (v == null) return null;
        try {
            return new BigDecimal(numeric(column, v));
        } catch (NumberFormatException e) {
            throw fail(column, v, ImportErrorCode.INVALID_NUMBER, "Not a number.");
        }
    }

    /** Decimal constrained to a range — used for lat/lng, which the DB does not check. */
    public BigDecimal decimal(String column, double min, double max) {
        BigDecimal d = decimal(column);
        if (d != null && (d.doubleValue() < min || d.doubleValue() > max)) {
            throw fail(column, d.toPlainString(), ImportErrorCode.OUT_OF_RANGE,
                "Must be between " + min + " and " + max + ".");
        }
        return d;
    }

    public Integer integer(String column) {
        String v = text(column);
        if (v == null) return null;
        try {
            return Integer.valueOf(numeric(column, v));
        } catch (NumberFormatException e) {
            throw fail(column, v, ImportErrorCode.INVALID_NUMBER, "Not a whole number.");
        }
    }

    public LocalDate date(String column) {
        String v = text(column);
        if (v == null) return null;
        for (DateTimeFormatter f : DATE_FORMATS) {
            try {
                return LocalDate.parse(v, f);
            } catch (Exception ignored) {
                // try the next accepted pattern
            }
        }
        throw fail(column, v, ImportErrorCode.INVALID_DATE, "Expected a date like 2026-03-04.");
    }

    /** Local date-time; the caller applies the application timezone. */
    public LocalDateTime dateTime(String column) {
        String v = text(column);
        if (v == null) return null;
        try {
            return LocalDateTime.parse(v.replace(' ', 'T'));
        } catch (Exception e) {
            LocalDate d = date(column);
            return d == null ? null : d.atStartOfDay();
        }
    }

    public Boolean bool(String column) {
        String v = text(column);
        if (v == null) return null;
        String n = ValueNormalizer.normalize(v, com.bellgado.logistics_ted.service.importer.ColumnType.BOOLEAN);
        if ("true".equals(n))  return Boolean.TRUE;
        if ("false".equals(n)) return Boolean.FALSE;
        throw fail(column, v, ImportErrorCode.INVALID_BOOLEAN, "Expected yes/no, true/false, 1/0 or да/не.");
    }

    /** Upper-cased and validated against the target column's CHECK constraint. */
    public String enumOf(String column, Set<String> allowed) {
        String v = text(column);
        if (v == null) return null;
        String upper = v.toUpperCase();
        if (!allowed.contains(upper)) {
            throw fail(column, v, ImportErrorCode.INVALID_ENUM,
                "Must be one of: " + String.join(", ", new java.util.TreeSet<>(allowed)) + ".");
        }
        return upper;
    }

    /** Pipe-separated members, trimmed, blanks dropped. */
    public List<String> pipeList(String column) {
        return ValueNormalizer.splitPipe(text(column));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Canonicalises a numeric cell to dot-decimal form.
     *
     * <p>Grouping separators are deliberately <b>not</b> supported. Accepting them would mean
     * stripping the "other" separator, and that silently turns a mis-flagged {@code 42,6977} into
     * {@code 426977} — a coordinate in the wrong hemisphere, or a quantity off by four orders of
     * magnitude, with nothing in the report to show for it. The separator that does not belong to
     * the declared format is therefore an error with a message that names the fix. Spreadsheet CSV
     * exports emit raw values without grouping, so nothing legitimate is lost.
     */
    private String numeric(String column, String v) {
        String s = v.replace(" ", "").replace((char) 0x00A0, ' ').replace(" ", "");
        char foreign = format.decimalComma() ? '.' : ',';
        if (s.indexOf(foreign) >= 0) {
            throw fail(column, v, ImportErrorCode.INVALID_NUMBER, format.decimalComma()
                ? "Contains '.', but this file is being read with ',' as the decimal separator."
                : "Contains ',', but this file is being read with '.' as the decimal separator. "
                    + "Re-send with decimal=comma if the sheet uses ',' for decimals.");
        }
        return format.decimalComma() ? s.replace(',', '.') : s;
    }

    private CsvValueException fail(String column, String value, ImportErrorCode code, String message) {
        return new CsvValueException(line, column, value, code, message);
    }

    private static String key(String column) {
        return column == null ? "" : column.trim().toLowerCase();
    }
}
