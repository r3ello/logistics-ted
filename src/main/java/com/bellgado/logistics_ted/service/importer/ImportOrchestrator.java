package com.bellgado.logistics_ted.service.importer;

import com.bellgado.logistics_ted.domain.ImportBatch;
import com.bellgado.logistics_ted.domain.ImportConflict;
import com.bellgado.logistics_ted.domain.ImportRef;
import com.bellgado.logistics_ted.repository.ImportConflictRepository;
import com.bellgado.logistics_ted.web.importer.ImportReport;
import com.bellgado.logistics_ted.web.importer.csv.CsvRow;
import com.bellgado.logistics_ted.web.importer.csv.CsvTable;
import com.bellgado.logistics_ted.web.importer.csv.CsvValueException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

/**
 * Runs one CSV file through the sync: parse-time validation, key resolution, three-way merge, and —
 * only in {@code apply} mode — the writes.
 *
 * <p><b>{@code validate} does not write and does not need to roll back.</b> The obvious
 * implementation of a dry run is "do everything inside a transaction and roll it back", but that
 * makes correctness depend on a rollback firing on every path, and a half-applied write is the one
 * failure this feature cannot afford. Skipping the write calls outright is both simpler and
 * unconditionally safe; the merge, the validation and the report are identical either way, which is
 * what makes {@code validate} a trustworthy pre-flight for a recurring run.
 *
 * <p>{@code @Component} rather than {@code @Service}: {@code ServiceLoggingAspect} logs
 * {@code @Service} arguments at DEBUG, and a {@code CsvTable} argument must never reach a log
 * (DATA_IMPORT_PLAN.md §6.9).
 */
@Component
public class ImportOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ImportOrchestrator.class);

    public static final String MODE_VALIDATE = "validate";
    public static final String MODE_APPLY = "apply";
    public static final String ON_ERROR_ABORT = "abort";

    private final ExternalKeyResolver keys;
    private final ImportConflictRepository conflicts;
    private final ImportBatchRecorder recorder;

    public ImportOrchestrator(ExternalKeyResolver keys, ImportConflictRepository conflicts,
                              ImportBatchRecorder recorder) {
        this.keys = keys;
        this.conflicts = conflicts;
        this.recorder = recorder;
    }

    /** @param onError {@code skip} (default) commits the good rows; {@code abort} rolls the file back. */
    @Transactional
    public ImportReport run(EntityImporter imp, CsvTable table, String mode, String onError,
                            String filename, String username) {

        boolean apply = MODE_APPLY.equals(mode);
        ImportBatch batch = recorder.start(imp.entityType(), apply ? MODE_APPLY : MODE_VALIDATE,
                                           filename, username);

        // Fail the whole file before touching a row if it cannot possibly be imported.
        List<String> required = new ArrayList<>(imp.requiredColumns());
        required.add(imp.keyColumn());
        table.requireColumns(required.toArray(new String[0]));

        Counters c = new Counters();
        List<ImportReport.Warning> warnings = new ArrayList<>();
        List<ImportReport.RowError> errors = new ArrayList<>();
        List<ImportReport.ConflictDetail> conflictDetails = new ArrayList<>();

        Set<String> known = new HashSet<>(imp.columns().keySet());
        known.add(imp.keyColumn());
        for (String unknown : table.unknownColumns(known)) {
            warnings.add(new ImportReport.Warning(0, ImportErrorCode.UNKNOWN_COLUMN, unknown,
                "Column is not managed by the '" + imp.name() + "' importer and was ignored."));
        }

        Map<String, ImportRef> refsByKey = keys.preload(imp.entityType(), collectKeys(imp, table));
        Set<String> seen = new LinkedHashSet<>();

        for (CsvRow row : table.rows()) {
            c.rowsRead++;
            try {
                String key = row.requiredText(imp.keyColumn());
                if (!seen.add(key)) {
                    // Not last-wins: two rows claiming one key means the sheet is wrong, and
                    // silently picking one would make the result depend on row order.
                    throw new CsvValueException(row.line(), imp.keyColumn(), key,
                        ImportErrorCode.DUPLICATE_KEY, "Key '" + key + "' appears more than once in this file.");
                }
                processRow(imp, row, key, refsByKey, batch, apply, c, warnings, conflictDetails);
            } catch (CsvValueException e) {
                c.failed++;
                errors.add(new ImportReport.RowError(
                    e.getLine(), e.getColumn(), e.getValue(), e.getCode(), e.getMessage()));
            } catch (RuntimeException e) {
                c.failed++;
                log.warn("[import] {} row {} failed: {}", imp.name(), row.line(), e.toString());
                errors.add(new ImportReport.RowError(row.line(), null, null,
                    ImportErrorCode.CONSTRAINT_VIOLATION, e.getMessage()));
            }
        }

        String status = c.failed == 0 && c.conflicts == 0 ? ImportReport.OK
                      : c.failed == c.rowsRead && c.rowsRead > 0 ? ImportReport.FAILED
                      : ImportReport.PARTIAL;

        ImportReport report = new ImportReport(
            batch.getPublicId(), imp.name(), apply ? MODE_APPLY : MODE_VALIDATE, status,
            c.rowsRead, c.created, c.updated, c.unchanged, c.keptApp, c.conflicts, c.failed,
            List.copyOf(warnings), List.copyOf(conflictDetails), List.copyOf(errors));

        recorder.finish(batch.getId(), report);

        if (apply && ON_ERROR_ABORT.equals(onError) && c.failed > 0) {
            // The batch row survives — it is written in its own transaction precisely for this.
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            log.warn("[import] {} aborted: {} failed row(s), file rolled back", imp.name(), c.failed);
        }
        return report;
    }

    private void processRow(EntityImporter imp, CsvRow row, String key, Map<String, ImportRef> refsByKey,
                            ImportBatch batch, boolean apply, Counters c,
                            List<ImportReport.Warning> warnings,
                            List<ImportReport.ConflictDetail> conflictDetails) {

        Map<String, String> sheet = imp.readRow(row);
        ImportRef ref = refsByKey.get(key);

        // A null projection means the id no longer resolves: the row was deleted through the UI and
        // the mapping is dangling. Re-create rather than fail — "never delete" also means the import
        // will not tidy up after itself, so a failed key could never recover.
        Map<String, String> app = ref == null ? null : imp.project(ref.getEntityId());
        boolean exists = app != null;
        if (ref != null && !exists) {
            warnings.add(new ImportReport.Warning(row.line(), ImportErrorCode.UNRESOLVED_REF, imp.keyColumn(),
                "Key '" + key + "' pointed at a deleted " + imp.entityType() + "; it was re-created."));
        }

        Map<String, String> appBase = exists ? ExternalKeyResolver.appBaselineOf(ref) : Map.of();
        Map<String, String> sheetBase = exists ? ExternalKeyResolver.sheetBaselineOf(ref) : Map.of();
        MergeResult result = ThreeWayMerger.merge(sheet, app, appBase, sheetBase, exists);

        switch (result.outcome()) {
            case CREATED -> {
                c.created++;
                if (apply) {
                    Long id = imp.create(sheet);
                    Map<String, String> post = imp.project(id);
                    if (ref == null) keys.record(imp.entityType(), key, id, batch.getId(), post, sheet);
                    else keys.repoint(ref, id, batch.getId(), post, sheet);
                }
            }
            case UPDATED -> {
                c.updated++;
                if (apply) {
                    imp.update(ref.getEntityId(), result.toApply());
                    keys.rebase(ref, imp.project(ref.getEntityId()), sheet, batch.getId());
                }
            }
            case KEPT_APP -> {
                c.keptApp++;
                // Re-base both sides, to different values: the app baseline advances to the winning
                // app value, the sheet baseline to the (stale) sheet value we just saw. Anything
                // else makes the next run either re-report the same app edit forever or overwrite
                // it with the stale sheet value.
                if (apply) keys.rebase(ref, app, sheet, batch.getId());
            }
            case UNCHANGED -> {
                c.unchanged++;
                if (apply && !ref.hasBaseline()) keys.rebase(ref, app, sheet, batch.getId());
            }
            case CONFLICT -> {
                c.conflicts++;
                recordConflicts(imp, row, key, ref, batch, apply, result, conflictDetails);
            }
            case FAILED -> c.failed++;
        }
    }

    /**
     * Conflicts are persisted only on {@code apply}. A dry run must leave no trace, and a validate
     * pass over a brand-new key has no {@code import_ref} row to hang a conflict off anyway.
     */
    private void recordConflicts(EntityImporter imp, CsvRow row, String key, ImportRef ref,
                                 ImportBatch batch, boolean apply, MergeResult result,
                                 List<ImportReport.ConflictDetail> details) {
        for (FieldConflict fc : result.conflicts()) {
            Long id = null;
            if (apply && ref != null) {
                // Match the open conflict for this (row, column) instead of duplicating it — an
                // unresolved conflict would otherwise pile up one row per run, forever.
                ImportConflict conflict = conflicts
                    .findByRefIdAndColumnNameAndResolvedAtIsNull(ref.getId(), fc.column())
                    .orElseGet(ImportConflict::new);
                conflict.setRefId(ref.getId());
                conflict.setBatchId(batch.getId());
                conflict.setEntityType(imp.entityType());
                conflict.setExternalKey(key);
                conflict.setColumnName(fc.column());
                conflict.setBaseValue(fc.base());
                conflict.setAppValue(fc.app());
                conflict.setSheetValue(fc.sheet());
                id = conflicts.save(conflict).getId();
            }
            details.add(new ImportReport.ConflictDetail(id, row.line(), key, fc.column(),
                fc.base(), fc.app(), fc.sheet(),
                "Changed in both the app and the sheet since the last sync. Row left untouched."));
        }
    }

    /** Lenient first pass: rows with no key fail later, in the main loop, with a proper error. */
    private static Set<String> collectKeys(EntityImporter imp, CsvTable table) {
        Set<String> out = new LinkedHashSet<>();
        for (CsvRow row : table.rows()) {
            String k = row.text(imp.keyColumn());
            if (k != null) out.add(k);
        }
        return out;
    }

    private static final class Counters {
        int rowsRead, created, updated, unchanged, keptApp, conflicts, failed;
    }
}
