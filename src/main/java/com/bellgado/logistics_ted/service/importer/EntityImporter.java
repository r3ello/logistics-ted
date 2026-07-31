package com.bellgado.logistics_ted.service.importer;

import com.bellgado.logistics_ted.web.importer.csv.CsvRow;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything the sync needs to know about one entity. Adding a new importable entity means writing
 * one of these and nothing else — the orchestrator, the merge, the conflict handling and the
 * {@code /api/import/entities} catalogue are all generic over this interface, so the published
 * documentation cannot drift from the code.
 *
 * <p><b>{@link #columns()} is the contract of what the sync owns.</b> A field absent from it can
 * never be written by an import and can never conflict — which is how {@code worker.username} and
 * {@code password_hash} are kept out of reach (re-assigning them on a recurring run would rotate
 * every crew's PIN and lock them out).
 */
public interface EntityImporter {

    /** File / URL segment, plural: {@code materials}, {@code houses}. */
    String name();

    /** Namespace for {@code import_ref.external_key}, singular: {@code material}, {@code house}. */
    String entityType();

    /** The columns this importer manages, and how each is canonicalised for comparison. */
    Map<String, ColumnType> columns();

    /** Subset of {@link #columns()} that must be present in the header and non-blank in every row. */
    Set<String> requiredColumns();

    /** Importers whose files must be applied first, by {@link #name()}. */
    default List<String> dependsOn() {
        return List.of();
    }

    /** The column carrying the sheet-owned external key. */
    default String keyColumn() {
        return "key";
    }

    /**
     * Validates one CSV row and returns its canonical values, keyed by column.
     *
     * <p>Only columns actually present in the file's header may appear in the result: "absent from
     * the header" means the file does not manage that field, which is not the same as an empty cell
     * (that is a value, and the merge will apply it).
     *
     * @throws com.bellgado.logistics_ted.web.importer.csv.CsvValueException on any invalid cell
     */
    Map<String, String> readRow(CsvRow row);

    /**
     * The entity's current values in canonical form, or {@code null} if the id no longer resolves —
     * which is how a mapping left dangling by a UI delete is detected and repaired.
     */
    Map<String, String> project(Long entityId);

    /** Creates the entity from canonical values and returns its new id. */
    Long create(Map<String, String> values);

    /** Applies just the given columns to an existing entity. */
    void update(Long entityId, Map<String, String> values);
}
