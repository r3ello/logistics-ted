package com.bellgado.logistics_ted.web.importer;

import com.bellgado.logistics_ted.service.importer.ImportErrorCode;
import java.util.List;
import java.util.UUID;

/**
 * The result of one import run, as returned by the API and stored in {@code import_batch.report_json}.
 *
 * <p>Everything here is row- and column-addressable on purpose. A recurring sync is a standing
 * conversation with whoever maintains the spreadsheet, and "constraint violation on row 402" is
 * useless to them.
 *
 * <p>It never contains file content beyond the individual offending values — see
 * DATA_IMPORT_PLAN.md §6.9.
 */
public record ImportReport(
    UUID batchId,
    String entity,
    String mode,
    String status,
    int rowsRead,
    int created,
    int updated,
    int unchanged,
    int keptApp,
    int conflicts,
    int failed,
    List<Warning> warnings,
    List<ConflictDetail> conflictDetails,
    List<RowError> errors
) {

    /** A cell or row the importer could not accept. */
    public record RowError(int line, String column, String value, ImportErrorCode code, String message) {}

    /** A column that moved on both sides — reported, never applied. */
    public record ConflictDetail(
        Long conflictId, int line, String key, String column,
        String base, String app, String sheet, String message) {}

    /** Non-fatal: the run continued. */
    public record Warning(int line, ImportErrorCode code, String column, String message) {}

    public static final String OK = "ok";
    public static final String PARTIAL = "partial";
    public static final String FAILED = "failed";
}
