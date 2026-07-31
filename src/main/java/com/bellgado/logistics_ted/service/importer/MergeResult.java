package com.bellgado.logistics_ted.service.importer;

import java.util.List;
import java.util.Map;

/**
 * What the three-way merge decided for one row.
 *
 * @param outcome        the single verdict for the row
 * @param toApply        columns whose sheet value should be written. <b>Empty unless
 *                       {@link RowOutcome#writes()}</b> — in particular a {@code CONFLICT} row
 *                       applies nothing at all, see {@link ThreeWayMerger}
 * @param conflicts      every column that moved on both sides
 * @param keptAppColumns columns where the app's value won because the sheet was stale
 * @param noBaseline     this key had never been applied, so "who changed it" could not be
 *                       established; the sheet was compared against the DB directly
 */
public record MergeResult(
    RowOutcome outcome,
    Map<String, String> toApply,
    List<FieldConflict> conflicts,
    List<String> keptAppColumns,
    boolean noBaseline
) {

    public boolean shouldWrite() {
        return outcome.writes() && !toApply.isEmpty();
    }

    public boolean hasConflicts() {
        return !conflicts.isEmpty();
    }
}
