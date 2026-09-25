package com.bellgado.logistics_ted.service.importer;

import com.bellgado.logistics_ted.domain.ImportRef;
import com.bellgado.logistics_ted.repository.ImportRefRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Translates the spreadsheet's external keys into our ids, and keeps the per-row merge baseline.
 *
 * <p>Deliberately a {@code @Component} and not a {@code @Service}: {@code ServiceLoggingAspect}
 * advises {@code @Service} beans under {@code ..service..} and logs their arguments at DEBUG, and
 * nothing in the import path should be able to spill file contents into a log
 * (DATA_IMPORT_PLAN.md §6.9).
 */
@Component
public class ExternalKeyResolver {

    private final ImportRefRepository refs;

    public ExternalKeyResolver(ImportRefRepository refs) {
        this.refs = refs;
    }

    /**
     * Loads every mapping a file needs in one query. Resolving key by key would cost one round trip
     * per row before any work happens — 800 of them for a single {@code house_stages.csv}.
     */
    public Map<String, ImportRef> preload(String entityType, Collection<String> keys) {
        Map<String, ImportRef> byKey = new HashMap<>();
        if (keys.isEmpty()) return byKey;
        for (ImportRef r : refs.findByEntityTypeAndExternalKeyIn(entityType, keys)) {
            byKey.put(r.getExternalKey(), r);
        }
        return byKey;
    }

    /** Creates the mapping for a freshly created entity. */
    public ImportRef record(String entityType, String externalKey, Long entityId, Long batchId,
                            Map<String, String> appSnapshot, Map<String, String> sheetSnapshot) {
        ImportRef ref = new ImportRef();
        ref.setEntityType(entityType);
        ref.setExternalKey(externalKey);
        ref.setEntityId(entityId);
        ref.setCreatedAt(Instant.now());
        applyBaseline(ref, appSnapshot, sheetSnapshot, batchId);
        return refs.save(ref);
    }

    /**
     * Links a key to an entity the app already holds under that external id — typically a house
     * created by hand with its CRM id typed in. The mapping is written <b>without a baseline</b>, the
     * same state {@code preseed-house-refs.sql} produces: the merge takes its no-baseline path, the
     * sheet is authoritative for this one run, and the run establishes both baselines.
     *
     * <p>{@code dangling} is an existing mapping whose record was deleted; it is re-pointed rather
     * than duplicated, since {@code (entity_type, external_key)} is unique. Its baselines described
     * the deleted record and are dropped.
     *
     * <p>With {@code persist=false} ({@code mode=validate}) nothing is saved and {@code dangling} is
     * left untouched: a detached copy is returned, so the dry run reports the row exactly as the real
     * run will — {@code updated}/{@code unchanged}, never {@code created} — while writing nothing.
     */
    public ImportRef adopt(String entityType, String externalKey, Long entityId, ImportRef dangling,
                           boolean persist) {
        ImportRef ref = persist && dangling != null ? dangling : new ImportRef();
        if (ref != dangling) {
            ref.setEntityType(entityType);
            ref.setExternalKey(externalKey);
            ref.setCreatedAt(Instant.now());
        }
        ref.setEntityId(entityId);
        ref.setSyncedSnapshot(null);
        ref.setSourceSnapshot(null);
        ref.setSourceHash(null);
        return persist ? refs.save(ref) : ref;
    }

    /**
     * Re-points a mapping whose entity was deleted through the UI. The alternative — failing the row
     * — would mean a key could never recover, since "never delete" also means the import will not
     * clean up after itself.
     */
    public ImportRef repoint(ImportRef ref, Long entityId, Long batchId,
                             Map<String, String> appSnapshot, Map<String, String> sheetSnapshot) {
        ref.setEntityId(entityId);
        applyBaseline(ref, appSnapshot, sheetSnapshot, batchId);
        return refs.save(ref);
    }

    /**
     * Moves both baselines to the row's post-run state.
     *
     * <p>Called after {@code KEPT_APP} too, not just after a write — but note the two snapshots
     * then hold <b>different</b> values: the app baseline advances to the winning app value while
     * the sheet baseline stays at the stale sheet value. That asymmetry is the point. Advancing
     * only one would leave the app edit re-detected forever; advancing both to the same value would
     * make the untouched sheet look changed and overwrite the app on the very next run.
     */
    public ImportRef rebase(ImportRef ref, Map<String, String> appSnapshot,
                            Map<String, String> sheetSnapshot, Long batchId) {
        applyBaseline(ref, appSnapshot, sheetSnapshot, batchId);
        return refs.save(ref);
    }

    private void applyBaseline(ImportRef ref, Map<String, String> appSnapshot,
                               Map<String, String> sheetSnapshot, Long batchId) {
        ref.setSyncedSnapshot(new LinkedHashMap<>(appSnapshot));
        ref.setSourceSnapshot(new LinkedHashMap<>(sheetSnapshot));
        ref.setSourceHash(RowHash.of(sheetSnapshot));
        ref.setLastSyncedAt(Instant.now());
        ref.setLastBatchId(batchId);
    }

    /** The app-side baseline as canonical strings. Empty when the key has never been applied. */
    public static Map<String, String> appBaselineOf(ImportRef ref) {
        return asStrings(ref == null ? null : ref.getSyncedSnapshot());
    }

    /** The sheet-side baseline as canonical strings. */
    public static Map<String, String> sheetBaselineOf(ImportRef ref) {
        return asStrings(ref == null ? null : ref.getSourceSnapshot());
    }

    private static Map<String, String> asStrings(Map<String, Object> snapshot) {
        Map<String, String> out = new LinkedHashMap<>();
        if (snapshot == null) return out;
        for (Map.Entry<String, Object> e : snapshot.entrySet()) {
            out.put(e.getKey(), e.getValue() == null ? null : String.valueOf(e.getValue()));
        }
        return out;
    }

    /** Keys mapped to an entity that no longer exists, for the report. */
    public List<ImportRef> mappingsFor(String entityType, Long entityId) {
        return refs.findByEntityTypeAndEntityId(entityType, entityId);
    }
}
