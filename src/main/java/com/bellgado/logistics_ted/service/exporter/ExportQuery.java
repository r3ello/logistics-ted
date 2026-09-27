package com.bellgado.logistics_ted.service.exporter;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The validated filter values for one export: only the filters the dataset declares, each parsed
 * to its type. Anything else in the request (column list, CSV dialect) is not a filter and is
 * never seen here.
 */
public final class ExportQuery {

    private final Map<String, Object> values;

    private ExportQuery(Map<String, Object> values) {
        this.values = values;
    }

    /**
     * Picks the declared filters out of the raw request parameters and parses them.
     *
     * @throws ExportException for a missing required filter or an unparseable value
     */
    public static ExportQuery parse(List<ExportFilter> declared, Map<String, String> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (ExportFilter f : declared) {
            String v = raw.get(f.name());
            if (v == null || v.isBlank()) {
                if (f.required()) {
                    throw new ExportException("Filter '" + f.name() + "' is required.");
                }
                continue;
            }
            out.put(f.name(), switch (f.type()) {
                case DATE -> parseDate(f.name(), v.trim());
                case INTEGER -> parseInteger(f.name(), v.trim());
                case TEXT -> v.trim();
            });
        }
        return new ExportQuery(out);
    }

    public LocalDate date(String name) {
        return (LocalDate) values.get(name);
    }

    public Integer integer(String name) {
        return (Integer) values.get(name);
    }

    public String text(String name) {
        return (String) values.get(name);
    }

    /** The parsed filters, for the audit trail. */
    public Map<String, Object> asMap() {
        return Collections.unmodifiableMap(values);
    }

    private static LocalDate parseDate(String name, String v) {
        try {
            return LocalDate.parse(v);
        } catch (DateTimeParseException e) {
            throw new ExportException("Filter '" + name + "' must be a date as YYYY-MM-DD, got '" + v + "'.");
        }
    }

    private static Integer parseInteger(String name, String v) {
        try {
            return Integer.valueOf(v);
        } catch (NumberFormatException e) {
            throw new ExportException("Filter '" + name + "' must be an integer, got '" + v + "'.");
        }
    }
}
