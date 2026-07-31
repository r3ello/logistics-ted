package com.bellgado.logistics_ted.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Maps a sheet-owned external key to one of our rows, and carries the three-way-merge baseline for
 * it.
 *
 * <p>It exists because almost no business table has a natural unique key — {@code house.name},
 * {@code material.name}, {@code worker.name}, {@code crew.name}, {@code supplier.name} and
 * {@code depot.name} are all non-unique, so a recurring sync cannot match CSV rows to DB rows by
 * name without duplicating them (DATA_IMPORT_PLAN.md §0.1).
 *
 * <p>Deliberately polymorphic with <b>no FK</b> to the mapped entity, mirroring {@code audit_log}'s
 * actor columns. A row deleted through the UI therefore leaves a dangling mapping; the resolver
 * detects that the id no longer exists and re-creates the entity for that key.
 */
@Entity
@Table(
    name = "import_ref",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_import_ref",
        columnNames = {"entity_type", "external_key"}
    )
)
@Getter
@Setter
@NoArgsConstructor
public class ImportRef {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Importer name — {@code house}, {@code material}, … Namespaces the external key. */
    @Column(name = "entity_type", nullable = false, length = 40)
    private String entityType;

    /** The key owned by the client's spreadsheet. Stable and never reused (DATA_IMPORT_PLAN.md §9.1). */
    @Column(name = "external_key", nullable = false, length = 120)
    private String externalKey;

    /** {@code bigint} so it fits both the {@code integer} and {@code bigint} PKs in the schema. */
    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    /** Whole-row SHA-256 of the last CSV values — the cheap "did anything move?" fingerprint. */
    @Column(name = "source_hash", length = 64)
    private String sourceHash;

    /**
     * The entity's values as of the last run — the baseline for "did the <b>app</b> change?".
     *
     * <p>Stored as values rather than a hash because the merge is per column and the conflict
     * report shows base / app / sheet side by side.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "synced_snapshot", columnDefinition = "jsonb")
    private Map<String, Object> syncedSnapshot = new LinkedHashMap<>();

    /**
     * The CSV's values as of the last run — the baseline for "did the <b>sheet</b> change?".
     *
     * <p>Separate from {@link #syncedSnapshot} because after a {@code KEPT_APP} the two sides are
     * legitimately out of sync: the app holds the winning value while the sheet still carries the
     * stale one. With a single baseline the next run reads that stale sheet value as a fresh change
     * and overwrites the app — the exact outcome {@code KEPT_APP} exists to prevent.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_snapshot", columnDefinition = "jsonb")
    private Map<String, Object> sourceSnapshot = new LinkedHashMap<>();

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    @Column(name = "last_batch_id")
    private Long lastBatchId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    /** True when this key has never been applied — the merge then has no baseline to compare against. */
    public boolean hasBaseline() {
        return syncedSnapshot != null && !syncedSnapshot.isEmpty();
    }
}
