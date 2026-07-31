package com.bellgado.logistics_ted.service.importer;

/**
 * How a column's values are canonicalised before the three-way comparison.
 *
 * <p>Without this the merge would report changes that aren't: {@code 24.5} vs {@code 24.50},
 * {@code IN_PROGRESS} vs {@code in_progress}, {@code "3|4|5"} vs {@code "5|3|4"}, or a stray
 * trailing space added by a spreadsheet — each would look like an edit and, on the other side,
 * manufacture a conflict.
 */
public enum ColumnType {

    /** Trimmed, internal whitespace collapsed; blank becomes null. */
    TEXT,

    /** Compared by value, not by representation — {@code 24.50} equals {@code 24.5}. */
    DECIMAL,

    INTEGER,

    /** ISO {@code YYYY-MM-DD}; {@code dd.MM.yyyy} and {@code dd/MM/yyyy} also accepted on input. */
    DATE,

    /** ISO local date-time. Zone handling is left to the importer, not baked into the CSV layer. */
    TIMESTAMP,

    /** Trimmed and upper-cased — the schema's CHECK constraints are all upper-case. */
    ENUM,

    /** {@code true/false}, {@code 1/0}, {@code yes/no}, {@code да/не}. */
    BOOLEAN,

    /** Pipe-separated and order-insensitive — {@code stage_orders} is a set, not a sequence. */
    PIPE_SET
}
