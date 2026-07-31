package com.bellgado.logistics_ted.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One CSV import run. Recorded for every {@code mode=apply} and every {@code mode=validate}, so the
 * recurring sync leaves an operational trail independent of the audit log.
 *
 * <p>The row counts mirror the six {@code RowOutcome} values — {@code unchanged} is expected to
 * dominate on a steady-state run, and a sudden drop in it is the cheapest signal that something
 * upstream (a re-keyed sheet, a changed delimiter) has gone wrong.
 */
@Entity
@Table(name = "import_batch")
@Getter
@Setter
@NoArgsConstructor
public class ImportBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The only id exposed by the API; the internal BIGINT never leaks. */
    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    /** Importer name, e.g. {@code houses}, {@code materials}. {@code batch} for a multi-file run. */
    @Column(name = "entity_type", nullable = false, length = 40)
    private String entityType;

    /** {@code validate} or {@code apply}. */
    @Column(nullable = false, length = 10)
    private String mode;

    @Column(length = 255)
    private String filename;

    /** Raw id, not a relation — the batch outlives the account that ran it. */
    @Column(name = "app_user_id")
    private Integer appUserId;

    @Column(length = 255)
    private String username;

    @Column(name = "rows_read",      nullable = false) private int rowsRead;
    @Column(name = "rows_created",   nullable = false) private int rowsCreated;
    @Column(name = "rows_updated",   nullable = false) private int rowsUpdated;
    @Column(name = "rows_unchanged", nullable = false) private int rowsUnchanged;
    @Column(name = "rows_kept_app",  nullable = false) private int rowsKeptApp;
    @Column(name = "rows_conflict",  nullable = false) private int rowsConflict;
    @Column(name = "rows_failed",    nullable = false) private int rowsFailed;

    /** {@code running} → {@code ok} / {@code partial} / {@code failed}. */
    @Column(nullable = false, length = 16)
    private String status;

    /**
     * The full report as returned by the API. Never contains file content — only counts, warnings,
     * conflicts and row-addressable errors (see DATA_IMPORT_PLAN.md §6.9).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "report_json", columnDefinition = "jsonb")
    private Map<String, Object> reportJson = new LinkedHashMap<>();
}
