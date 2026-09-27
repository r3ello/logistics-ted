package com.bellgado.logistics_ted.service.exporter;

/**
 * A query parameter a dataset accepts to narrow its rows. Declared by the exporter so the
 * catalogue can describe it and {@link ExportQuery} can type-check it before the exporter runs.
 *
 * @param name        the query parameter name
 * @param type        {@link Type#DATE} (ISO {@code YYYY-MM-DD}), {@link Type#INTEGER} or
 *                    {@link Type#TEXT} (trimmed, matched exactly)
 * @param required    a missing required filter is a 400, not an unbounded export
 * @param description one line for the catalogue
 */
public record ExportFilter(String name, Type type, boolean required, String description) {

    public enum Type { DATE, INTEGER, TEXT }

    public static ExportFilter requiredDate(String name, String description) {
        return new ExportFilter(name, Type.DATE, true, description);
    }

    public static ExportFilter optionalInteger(String name, String description) {
        return new ExportFilter(name, Type.INTEGER, false, description);
    }

    public static ExportFilter optionalText(String name, String description) {
        return new ExportFilter(name, Type.TEXT, false, description);
    }
}
