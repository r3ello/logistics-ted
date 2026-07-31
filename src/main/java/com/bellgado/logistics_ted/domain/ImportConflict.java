package com.bellgado.logistics_ted.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One column that moved on <b>both</b> sides since the last sync. The locked policy is report-and-skip:
 * the importer never guesses which side is right, so the row is left untouched and the divergence is
 * recorded here.
 *
 * <p>It is a table rather than a line in the run report because an unresolved conflict would
 * otherwise be re-reported on every run forever. Rows are matched to the open conflict for the same
 * {@code (ref_id, column_name)} instead of being duplicated, and are closed either by an explicit
 * resolution or by the two sides converging on their own.
 */
@Entity
@Table(name = "import_conflict")
@Getter
@Setter
@NoArgsConstructor
public class ImportConflict {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ref_id", nullable = false)
    private Long refId;

    /** The run that first detected it. */
    @Column(name = "batch_id")
    private Long batchId;

    @Column(name = "entity_type", nullable = false, length = 40)
    private String entityType;

    @Column(name = "external_key", nullable = false, length = 120)
    private String externalKey;

    @Column(name = "column_name", nullable = false, length = 64)
    private String columnName;

    /** What the last successful sync wrote. */
    @Column(name = "base_value", columnDefinition = "text")
    private String baseValue;

    /** What the DB holds now — i.e. what someone changed in the app. */
    @Column(name = "app_value", columnDefinition = "text")
    private String appValue;

    /** What the incoming CSV brings. */
    @Column(name = "sheet_value", columnDefinition = "text")
    private String sheetValue;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt = Instant.now();

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    /** {@code sheet} | {@code app} | {@code manual} — null while open. */
    @Column(length = 16)
    private String resolution;

    public boolean isOpen() {
        return resolvedAt == null;
    }
}
