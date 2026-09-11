package com.bellgado.logistics_ted.gps;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Set;

/**
 * Lenient conversions for GPS.bg values. Everything arrives as text and the declared {@code xsi:type}
 * is unreliable (an {@code xsd:int} field carries "1.62"), so a value that does not parse becomes
 * {@code null} rather than failing the whole poll.
 */
final class GpsValues {

    /** GPS.bg's timestamp format, in both directions. The zone is implicit: Bulgarian local time. */
    static final DateTimeFormatter PROVIDER_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** GPS.bg's placeholder for "no driver identified". It is not a name. */
    private static final Set<String> NO_VALUE = Set.of("Неизвестен");

    private GpsValues() {
    }

    /** GetStatus wraps strings in literal quotes ({@code "51712"}); strips one surrounding pair. */
    static String unquote(String v) {
        if (v != null && v.length() >= 2 && v.charAt(0) == '"' && v.charAt(v.length() - 1) == '"') {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }

    static String text(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() || NO_VALUE.contains(t) ? null : t;
    }

    static Double dbl(String v) {
        if (v == null || v.isBlank()) return null;
        try {
            double d = Double.parseDouble(v.trim());
            return Double.isFinite(d) ? d : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Integer integer(String v) {
        Double d = dbl(v);
        return d == null ? null : (int) Math.round(d);
    }

    static Boolean bool(String v) {
        if (v == null) return null;
        return switch (v.trim().toLowerCase()) {
            case "1", "true" -> Boolean.TRUE;
            case "0", "false" -> Boolean.FALSE;
            default -> null;
        };
    }

    /** Provider local time to an instant; "0000-00-00 00:00:00" and garbage become null. */
    static Instant ts(String v, ZoneId zone) {
        if (v == null || v.isBlank()) return null;
        try {
            return LocalDateTime.parse(v.trim(), PROVIDER_TS).atZone(zone).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
