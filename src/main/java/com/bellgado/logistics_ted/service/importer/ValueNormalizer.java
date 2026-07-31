package com.bellgado.logistics_ted.service.importer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Canonicalises a value so the three-way merge compares meaning rather than representation.
 *
 * <p>Pure and Spring-free on purpose: it holds no state, needs no context, and staying outside the
 * {@code @Service} bean graph keeps it out of {@code ServiceLoggingAspect}'s reach (which logs
 * arguments at DEBUG).
 *
 * <p><b>Normalisation never throws.</b> It runs over values coming out of the database as well as
 * out of the CSV, and a single odd legacy value must not be able to abort a whole run — anything
 * unparseable degrades to its trimmed text form, which simply compares unequal and shows up as a
 * normal change or conflict. Input validation is the parser's job (see {@code CsvRow}), not this
 * class's.
 */
public final class ValueNormalizer {

    private ValueNormalizer() {}

    private static final DateTimeFormatter[] DATE_FORMATS = {
        DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ofPattern("dd.MM.yyyy"),
        DateTimeFormatter.ofPattern("dd/MM/yyyy"),
        DateTimeFormatter.ofPattern("d.M.yyyy"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd")
    };

    private static final Set<String> TRUE_WORDS  = Set.of("true", "1", "yes", "y", "да", "istina");
    private static final Set<String> FALSE_WORDS = Set.of("false", "0", "no", "n", "не");

    /** Non-breaking space — written as an escape because a literal one is invisible in source. */
    private static final char NBSP = 0x00A0;

    /** Canonical form, or {@code null} for "no value". */
    public static String normalize(Object value, ColumnType type) {
        if (value == null) return null;
        String raw = collapse(String.valueOf(value));
        if (raw.isEmpty()) return null;

        return switch (type) {
            case TEXT      -> raw;
            case DECIMAL   -> decimal(value, raw);
            case INTEGER   -> integer(value, raw);
            case DATE      -> date(value, raw);
            case TIMESTAMP -> timestamp(value, raw);
            case ENUM      -> raw.toUpperCase();
            case BOOLEAN   -> bool(raw);
            case PIPE_SET  -> pipeSet(raw);
        };
    }

    /** Normalises a whole projection in one go. Keys are kept as given; null values are kept as null. */
    public static Map<String, String> normalizeAll(Map<String, ?> values, Map<String, ColumnType> types) {
        Map<String, String> out = new LinkedHashMap<>();
        if (values == null) return out;
        for (Map.Entry<String, ?> e : values.entrySet()) {
            ColumnType t = types.getOrDefault(e.getKey(), ColumnType.TEXT);
            out.put(e.getKey(), normalize(e.getValue(), t));
        }
        return out;
    }

    /** Null-safe equality over canonical forms. */
    public static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    // ── per-type canonicalisation ─────────────────────────────────────────────

    private static String decimal(Object value, String raw) {
        try {
            BigDecimal d = (value instanceof BigDecimal bd) ? bd
                         : (value instanceof Number n)      ? new BigDecimal(n.toString())
                         : new BigDecimal(raw);
            // stripTrailingZeros so 24.50, 24.5 and 24.500 all collapse to the same string.
            return d.stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    private static String integer(Object value, String raw) {
        try {
            if (value instanceof Number n) return Long.toString(n.longValue());
            return Long.toString(Long.parseLong(raw));
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    private static String date(Object value, String raw) {
        if (value instanceof LocalDate d) return d.toString();
        if (value instanceof LocalDateTime dt) return dt.toLocalDate().toString();
        for (DateTimeFormatter f : DATE_FORMATS) {
            try {
                return LocalDate.parse(raw, f).toString();
            } catch (Exception ignored) {
                // try the next pattern
            }
        }
        return raw;
    }

    private static String timestamp(Object value, String raw) {
        if (value instanceof LocalDateTime dt) return dt.toString();
        try {
            return LocalDateTime.parse(raw).toString();
        } catch (Exception e) {
            return date(value, raw);
        }
    }

    private static String bool(String raw) {
        String lower = raw.toLowerCase();
        if (TRUE_WORDS.contains(lower))  return "true";
        if (FALSE_WORDS.contains(lower)) return "false";
        return raw;
    }

    /** Sorted + de-duplicated so {@code "5|3|4"} and {@code "3|4|5"} compare equal. */
    private static String pipeSet(String raw) {
        Set<String> parts = new TreeSet<>();
        for (String p : raw.split("\\|")) {
            String t = p.trim();
            if (!t.isEmpty()) parts.add(t);
        }
        return parts.isEmpty() ? null : String.join("|", parts);
    }

    /** Splits a pipe cell into its members, trimmed, blanks dropped, original order preserved. */
    public static List<String> splitPipe(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String p : Arrays.asList(raw.split("\\|"))) {
            String t = p.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** Trim + collapse internal whitespace runs (including the NBSP that spreadsheets love). */
    private static String collapse(String s) {
        return s.replace(NBSP, ' ').trim().replaceAll("\\s+", " ");
    }
}
