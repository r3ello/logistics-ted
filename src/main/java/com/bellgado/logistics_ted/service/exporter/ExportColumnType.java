package com.bellgado.logistics_ted.service.exporter;

/**
 * How a column's value is rendered into a CSV cell. The type decides the textual form
 * ({@code decimal} honours the requested decimal separator, {@code timestamp} the requested time
 * format) and whether the formula-injection guard applies — only free-text columns get it, so a
 * negative number is never mangled into {@code '-5}.
 */
public enum ExportColumnType {
    TEXT,
    INTEGER,
    DECIMAL,
    DATE,
    TIMESTAMP,
    BOOLEAN,
    ENUM,
    /** Several values in one cell, pipe-separated — the same convention the import accepts. */
    PIPE_SET
}
