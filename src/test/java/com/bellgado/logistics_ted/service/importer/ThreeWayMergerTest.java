package com.bellgado.logistics_ted.service.importer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The safety net for the recurring sync. Every one of these cases is a way to silently destroy live
 * data if the merge gets it wrong: {@link #appChangedSheetStale_keepsAppValue()} is the one that
 * stops a weekly import from reverting what a crew leader did on Tuesday, and
 * {@link #conflictBlocksTheWholeRow()} is the one that stops half a coupled row being written.
 */
class ThreeWayMergerTest {

    private static Map<String, String> map(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    // ── the four quadrants ────────────────────────────────────────────────────

    @Test
    void neitherSideMoved_isUnchangedAndWritesNothing() {
        MergeResult r = ThreeWayMerger.merge(
            map("name", "Къща Иванов", "price", "24.5"),
            map("name", "Къща Иванов", "price", "24.5"),
            map("name", "Къща Иванов", "price", "24.5"),
            true);

        assertThat(r.outcome()).isEqualTo(RowOutcome.UNCHANGED);
        assertThat(r.toApply()).isEmpty();
        assertThat(r.shouldWrite()).isFalse();
        assertThat(r.conflicts()).isEmpty();
    }

    @Test
    void sheetMovedAppDidNot_appliesTheSheetValue() {
        MergeResult r = ThreeWayMerger.merge(
            map("price", "26.00"),
            map("price", "24.5"),
            map("price", "24.5"),
            true);

        assertThat(r.outcome()).isEqualTo(RowOutcome.UPDATED);
        assertThat(r.toApply()).containsExactly(Map.entry("price", "26.00"));
        assertThat(r.shouldWrite()).isTrue();
    }

    @Test
    void appChangedSheetStale_keepsAppValue() {
        // A crew leader set the stage to DONE in the app; the sheet still shows the old value.
        MergeResult r = ThreeWayMerger.merge(
            map("status", "IN_PROGRESS"),
            map("status", "DONE"),
            map("status", "IN_PROGRESS"),
            true);

        assertThat(r.outcome()).isEqualTo(RowOutcome.KEPT_APP);
        assertThat(r.toApply()).isEmpty();
        assertThat(r.keptAppColumns()).containsExactly("status");
    }

    @Test
    void bothMoved_isAConflictAndWritesNothing() {
        MergeResult r = ThreeWayMerger.merge(
            map("status", "NOT_STARTED"),
            map("status", "DONE"),
            map("status", "IN_PROGRESS"),
            true);

        assertThat(r.outcome()).isEqualTo(RowOutcome.CONFLICT);
        assertThat(r.shouldWrite()).isFalse();
        assertThat(r.conflicts()).containsExactly(
            new FieldConflict("status", "IN_PROGRESS", "DONE", "NOT_STARTED"));
    }

    // ── the rules that keep the four quadrants honest ─────────────────────────

    @Test
    void bothMovedToTheSameValue_isConvergenceNotConflict() {
        MergeResult r = ThreeWayMerger.merge(
            map("status", "DONE"),
            map("status", "DONE"),
            map("status", "IN_PROGRESS"),
            true);

        assertThat(r.outcome()).isEqualTo(RowOutcome.UNCHANGED);
        assertThat(r.conflicts()).isEmpty();
    }

    @Test
    void conflictBlocksTheWholeRow() {
        // status conflicts, notes would update cleanly. Applying only notes would leave the row
        // internally inconsistent (status and its dates are coupled), so nothing is written.
        MergeResult r = ThreeWayMerger.merge(
            map("status", "NOT_STARTED", "notes", "нова бележка"),
            map("status", "DONE",        "notes", "стара"),
            map("status", "IN_PROGRESS", "notes", "стара"),
            true);

        assertThat(r.outcome()).isEqualTo(RowOutcome.CONFLICT);
        assertThat(r.toApply()).isEmpty();
        assertThat(r.conflicts()).hasSize(1);
        assertThat(r.conflicts().get(0).column()).isEqualTo("status");
    }

    @Test
    void unrelatedAppEditDoesNotBlockACleanUpdateOnAnotherColumn() {
        // Per-column detection: someone fixed the phone in the app, the sheet raised the price.
        // Both must go through — a row-level comparison would have called this a conflict.
        MergeResult r = ThreeWayMerger.merge(
            map("price", "26.00", "phone", "+359888111222"),
            map("price", "24.5",  "phone", "+359888999888"),
            map("price", "24.5",  "phone", "+359888111222"),
            true);

        assertThat(r.outcome()).isEqualTo(RowOutcome.UPDATED);
        assertThat(r.toApply()).containsOnlyKeys("price");
        assertThat(r.keptAppColumns()).containsExactly("phone");
        assertThat(r.conflicts()).isEmpty();
    }

    // ── creation and first-run behaviour ──────────────────────────────────────

    @Test
    void unknownKey_isCreatedFromTheSheet() {
        MergeResult r = ThreeWayMerger.merge(map("name", "Нова къща"), Map.of(), null, false);

        assertThat(r.outcome()).isEqualTo(RowOutcome.CREATED);
        assertThat(r.toApply()).containsExactly(Map.entry("name", "Нова къща"));
        assertThat(r.shouldWrite()).isTrue();
    }

    @Test
    void noBaseline_comparesSheetAgainstDbAndFlagsIt() {
        // First run over a pre-existing database: nothing to attribute changes to, so the sheet is
        // authoritative and the report says so. mode=validate is the default precisely for this.
        MergeResult r = ThreeWayMerger.merge(
            map("name", "Къща Иванов", "location", "с. Бистрица"),
            map("name", "Kashta Ivanov", "location", "с. Бистрица"),
            null,
            true);

        assertThat(r.outcome()).isEqualTo(RowOutcome.UPDATED);
        assertThat(r.noBaseline()).isTrue();
        assertThat(r.toApply()).containsOnlyKeys("name");
        assertThat(r.conflicts()).isEmpty();
    }

    @Test
    void noBaselineAndIdentical_isUnchanged() {
        MergeResult r = ThreeWayMerger.merge(
            map("name", "Къща Иванов"), map("name", "Къща Иванов"), Map.of(), true);

        assertThat(r.outcome()).isEqualTo(RowOutcome.UNCHANGED);
        assertThat(r.noBaseline()).isTrue();
    }

    // ── divergent baselines, the state a KEPT_APP leaves behind ───────────────

    @Test
    void afterKeptApp_theStaleSheetDoesNotOverwriteTheAppOnTheNextRun() {
        // Run 1 wrote 10. The app then moved to 99 and run 2 resolved KEPT_APP, advancing the app
        // baseline to 99 while the sheet baseline stayed at 10. Run 3 must be a no-op — with a
        // single shared baseline it would read the untouched sheet as a change and revert the app.
        MergeResult r = ThreeWayMerger.merge(
            map("price", "10"),     // sheet, unchanged since run 1
            map("price", "99"),     // app, edited by a user
            map("price", "99"),     // app baseline, advanced by KEPT_APP
            map("price", "10"),     // sheet baseline, still the stale value
            true);

        assertThat(r.outcome()).isEqualTo(RowOutcome.UNCHANGED);
        assertThat(r.toApply()).isEmpty();
    }

    @Test
    void afterKeptApp_aRealSheetEditStillWins() {
        // The client finally corrects the sheet: that is newer than the app edit, so it applies.
        MergeResult r = ThreeWayMerger.merge(
            map("price", "15"),     // sheet, now edited
            map("price", "99"),
            map("price", "99"),
            map("price", "10"),
            true);

        assertThat(r.outcome()).isEqualTo(RowOutcome.UPDATED);
        assertThat(r.toApply()).containsExactly(Map.entry("price", "15"));
    }

    @Test
    void afterKeptApp_bothMovingAgainIsStillAConflict() {
        MergeResult r = ThreeWayMerger.merge(
            map("price", "15"),     // sheet moved from its baseline of 10
            map("price", "77"),     // app moved again from its baseline of 99
            map("price", "99"),
            map("price", "10"),
            true);

        assertThat(r.outcome()).isEqualTo(RowOutcome.CONFLICT);
        assertThat(r.conflicts()).containsExactly(new FieldConflict("price", "99", "77", "15"));
    }

    // ── nulls ─────────────────────────────────────────────────────────────────

    @Test
    void clearingAValueInTheSheetIsAnUpdate() {
        MergeResult r = ThreeWayMerger.merge(
            map("notes", null), map("notes", "стара"), map("notes", "стара"), true);

        assertThat(r.outcome()).isEqualTo(RowOutcome.UPDATED);
        assertThat(r.toApply()).containsOnlyKeys("notes");
        assertThat(r.toApply().get("notes")).isNull();
    }

    @Test
    void columnsAbsentFromTheSheetAreNeverTouched() {
        // "not in the header" means the file does not own the field — it must not be compared,
        // let alone nulled out.
        MergeResult r = ThreeWayMerger.merge(
            map("name", "Къща Иванов"),
            map("name", "Къща Иванов", "location", "с. Бистрица"),
            map("name", "Къща Иванов", "location", "друго място"),
            true);

        assertThat(r.outcome()).isEqualTo(RowOutcome.UNCHANGED);
        assertThat(r.conflicts()).isEmpty();
        assertThat(r.keptAppColumns()).isEmpty();
    }
}
