package com.bellgado.logistics_ted.service.importer;

/**
 * Row- and file-level failure codes surfaced in the import report. Stable strings — the client's
 * spreadsheet maintainer reads them, and the FE may map them to translated messages.
 */
public enum ImportErrorCode {

    // ── file level ────────────────────────────────────────────────────────────
    /** Bytes are not valid UTF-8. Never transcode on a guess — the data is Cyrillic. */
    ENCODING,
    MALFORMED_CSV,
    NO_HEADER,
    DUPLICATE_COLUMN,
    MISSING_COLUMN,
    ROW_LIMIT_EXCEEDED,

    // ── row level ─────────────────────────────────────────────────────────────
    EMPTY_REQUIRED,
    /** The same external key appears twice in one file. Not silently last-wins. */
    DUPLICATE_KEY,
    /** A {@code *_key} column points at a key no previous file created. */
    UNRESOLVED_REF,
    INVALID_NUMBER,
    INVALID_DATE,
    INVALID_ENUM,
    INVALID_BOOLEAN,
    /** lat/lng outside the valid range — the DB has no CHECK for these, so we must. */
    OUT_OF_RANGE,
    TOO_LONG,
    CONSTRAINT_VIOLATION,
    /** A key whose identity fields changed wholesale — likely reused for a different entity. */
    KEY_REUSED,

    // ── warnings ──────────────────────────────────────────────────────────────
    UNKNOWN_COLUMN
}
