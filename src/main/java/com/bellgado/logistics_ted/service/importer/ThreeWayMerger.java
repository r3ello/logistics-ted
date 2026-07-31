package com.bellgado.logistics_ted.service.importer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The core of the recurring sync: decides, per row, what the CSV is allowed to change.
 *
 * <p>Between two runs <b>both sides move</b> — the client edits the spreadsheet while crew leaders,
 * dispatchers and admins edit the same records in the app. A plain "apply what the CSV says" would
 * silently overwrite live operational state on every run. So each column is compared three ways:
 * the incoming <b>sheet</b> value, the current <b>app</b> value, and the <b>baseline</b> — the value
 * this importer last wrote, kept in {@code import_ref.synced_snapshot}.
 *
 * <pre>
 *   app changed | sheet changed | outcome
 *   ------------+---------------+-----------------------------------------
 *      no       |      no       | UNCHANGED  — nothing written
 *      no       |     yes       | UPDATED    — sheet value applied
 *     yes       |      no       | KEPT_APP   — sheet is stale, app wins
 *     yes       |     yes       | CONFLICT   — reported, nothing written
 * </pre>
 *
 * <p><b>Detection is per column; application is per row.</b> Detecting per column avoids
 * manufacturing conflicts — someone fixing a phone number in the app must not block a price update
 * from the sheet, and the report can name exactly which columns diverged. But once any column
 * conflicts, the <i>whole row</i> is skipped: fields in this schema are coupled
 * ({@code status} with {@code start_date}/{@code end_date}, quantity with unit), and applying half
 * of them would leave a row that is internally inconsistent — worse than applying none.
 *
 * <p><b>Convergence is not a conflict.</b> If both sides moved to the <i>same</i> value there is
 * nothing to decide, so it counts as unchanged rather than flagging a divergence that no longer exists.
 *
 * <p><b>No baseline.</b> The first run over a pre-existing database has no snapshot to compare
 * against, so "who changed what" is unknowable. Rather than flagging every difference as a conflict
 * (unusable noise on day one) the sheet is compared directly against the DB and differences are
 * reported as ordinary updates, with {@link MergeResult#noBaseline()} set. Because
 * {@code mode=validate} is the default, a human sees exactly what that first sync would change
 * before anything is written.
 *
 * <p>Pure and Spring-free: no state, no context, and staying out of the {@code @Service} bean graph
 * keeps it clear of {@code ServiceLoggingAspect}, which logs arguments at DEBUG.
 */
public final class ThreeWayMerger {

    private ThreeWayMerger() {}

    /**
     * Convenience for the synchronized case, where both baselines are the same — a row that has
     * never diverged. Prefer the five-argument form; see its note on why the two differ.
     */
    public static MergeResult merge(Map<String, String> sheet,
                                    Map<String, String> app,
                                    Map<String, String> baseline,
                                    boolean entityExists) {
        return merge(sheet, app, baseline, baseline, entityExists);
    }

    /**
     * @param sheet         normalised CSV values, keyed by column — only the columns this file
     *                      manages. A column absent from the header must be absent here: it means
     *                      "this file does not own that field", which is not the same as an empty cell
     * @param app           normalised current DB projection for the same columns
     * @param appBaseline   the entity's values as of the last run → "did the app change?"
     * @param sheetBaseline the CSV's values as of the last run → "did the sheet change?"
     * @param entityExists  whether the external key already resolves to a live row
     *
     * <p>The two baselines are usually identical, and diverge exactly when a previous run ended in
     * {@code KEPT_APP}: the app won, so its value became the app baseline, while the sheet baseline
     * stays at the stale value the client has not yet corrected. Comparing the incoming sheet
     * against the <i>app</i> baseline in that state would read the unchanged sheet as a fresh edit
     * and overwrite the app on the very next run.
     */
    public static MergeResult merge(Map<String, String> sheet,
                                    Map<String, String> app,
                                    Map<String, String> appBaseline,
                                    Map<String, String> sheetBaseline,
                                    boolean entityExists) {

        if (!entityExists) {
            // Nothing to reconcile — every column of a new row comes from the sheet.
            return new MergeResult(RowOutcome.CREATED, new LinkedHashMap<>(sheet),
                                   List.of(), List.of(), true);
        }

        boolean noBaseline = appBaseline == null || appBaseline.isEmpty();

        Map<String, String> toApply = new LinkedHashMap<>();
        List<FieldConflict> conflicts = new ArrayList<>();
        List<String> keptApp = new ArrayList<>();

        for (Map.Entry<String, String> entry : sheet.entrySet()) {
            String column = entry.getKey();
            String sheetValue = entry.getValue();
            String appValue = app == null ? null : app.get(column);
            // With no baseline we cannot attribute a difference to either side. Anchoring both
            // baselines on the current app value makes the sheet authoritative for this first run,
            // which is both the useful behaviour and the reviewable one (validate mode shows it
            // before it happens).
            String appBase = noBaseline ? appValue : appBaseline.get(column);
            String sheetBase = noBaseline ? appValue
                             : sheetBaseline == null ? appBase : sheetBaseline.get(column);

            boolean appChanged = !ValueNormalizer.eq(appValue, appBase);
            boolean sheetChanged = !ValueNormalizer.eq(sheetValue, sheetBase);

            if (!appChanged && !sheetChanged) {
                continue;                                  // neither side moved
            }
            if (!appChanged) {
                toApply.put(column, sheetValue);           // only the sheet moved
            } else if (!sheetChanged) {
                keptApp.add(column);                       // only the app moved
            } else if (ValueNormalizer.eq(sheetValue, appValue)) {
                continue;                                  // both moved to the same value
            } else {
                conflicts.add(new FieldConflict(column, appBase, appValue, sheetValue));
            }
        }

        // A conflict anywhere blocks the whole row — see the class comment on coupled fields.
        if (!conflicts.isEmpty()) {
            return new MergeResult(RowOutcome.CONFLICT, Map.of(), List.copyOf(conflicts),
                                   List.copyOf(keptApp), noBaseline);
        }
        RowOutcome outcome = !toApply.isEmpty() ? RowOutcome.UPDATED
                           : !keptApp.isEmpty() ? RowOutcome.KEPT_APP
                           : RowOutcome.UNCHANGED;
        return new MergeResult(outcome, toApply, List.of(), List.copyOf(keptApp), noBaseline);
    }
}
