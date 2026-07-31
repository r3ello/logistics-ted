package com.bellgado.logistics_ted.service.importer;

/**
 * What the sync decided for one CSV row. Every row resolves to exactly one of these and the run
 * report counts them (DATA_IMPORT_PLAN.md §4.1).
 */
public enum RowOutcome {

    /** Key unknown — a new entity is created and the mapping recorded. */
    CREATED,

    /** The sheet moved, the app didn't — the sheet value is applied. */
    UPDATED,

    /**
     * Neither side moved. Expected to dominate a steady-state run, and deliberately writes nothing:
     * no {@code updated_at} churn, no audit noise, no duplicate {@code house_stage_crew_log} rows.
     */
    UNCHANGED,

    /** The app moved, the sheet didn't — the app value survives and the baseline is re-based. */
    KEPT_APP,

    /** Both moved. Nothing is written; the divergence is recorded in {@code import_conflict}. */
    CONFLICT,

    /** Validation or a DB constraint rejected the row. */
    FAILED;

    /** Only these two ever write business data. */
    public boolean writes() {
        return this == CREATED || this == UPDATED;
    }
}
