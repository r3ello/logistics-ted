package com.bellgado.logistics_ted.service.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bellgado.logistics_ted.domain.ImportBatch;
import com.bellgado.logistics_ted.domain.ImportConflict;
import com.bellgado.logistics_ted.domain.ImportRef;
import com.bellgado.logistics_ted.repository.ImportConflictRepository;
import com.bellgado.logistics_ted.repository.ImportRefRepository;
import com.bellgado.logistics_ted.web.importer.ImportReport;
import com.bellgado.logistics_ted.web.importer.csv.CsvException;
import com.bellgado.logistics_ted.web.importer.csv.CsvFormat;
import com.bellgado.logistics_ted.web.importer.csv.CsvReader;
import com.bellgado.logistics_ted.web.importer.csv.CsvRow;
import com.bellgado.logistics_ted.web.importer.csv.CsvTable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end coverage of one file through the sync, against an in-memory entity store.
 *
 * <p>The case that matters most is {@link #runningTheSameFileTwiceChangesNothingTheSecondTime()} —
 * that is the rehearsal check from DATA_IMPORT_PLAN.md §8 phase 8, and the property the whole
 * recurring design rests on. If a steady-state run does not settle to all-{@code unchanged}, the
 * baseline is not being re-based correctly and every future run will rewrite rows it should not.
 */
class ImportOrchestratorTest {

    private ImportOrchestrator orchestrator;
    private FakeImporter importer;
    private Map<String, ImportRef> refs;
    private List<ImportConflict> savedConflicts;

    @BeforeEach
    void setUp() {
        importer = new FakeImporter();
        refs = new HashMap<>();
        savedConflicts = new ArrayList<>();

        AtomicLong refIds = new AtomicLong();
        ImportRefRepository refRepo = mock(ImportRefRepository.class);
        when(refRepo.findByEntityTypeAndExternalKeyIn(any(), any())).thenAnswer(inv -> {
            Collection<String> wanted = inv.getArgument(1);
            return wanted.stream().map(refs::get).filter(Objects::nonNull).toList();
        });
        when(refRepo.save(any(ImportRef.class))).thenAnswer(inv -> {
            ImportRef r = inv.getArgument(0);
            if (r.getId() == null) r.setId(refIds.incrementAndGet());
            refs.put(r.getExternalKey(), r);
            return r;
        });

        ImportConflictRepository conflictRepo = mock(ImportConflictRepository.class);
        when(conflictRepo.findByRefIdAndColumnNameAndResolvedAtIsNull(any(), any()))
            .thenReturn(Optional.empty());
        AtomicLong conflictIds = new AtomicLong();
        when(conflictRepo.save(any(ImportConflict.class))).thenAnswer(inv -> {
            ImportConflict k = inv.getArgument(0);
            if (k.getId() == null) k.setId(conflictIds.incrementAndGet());
            savedConflicts.add(k);
            return k;
        });

        ImportBatchRecorder recorder = mock(ImportBatchRecorder.class);
        when(recorder.start(any(), any(), any(), any())).thenAnswer(inv -> {
            ImportBatch b = new ImportBatch();
            b.setId(1L);
            b.setPublicId(UUID.randomUUID());
            return b;
        });

        orchestrator = new ImportOrchestrator(new ExternalKeyResolver(refRepo), conflictRepo, recorder);
    }

    private ImportReport apply(String csv) {
        return run(csv, ImportOrchestrator.MODE_APPLY);
    }

    private ImportReport run(String csv, String mode) {
        CsvTable table = CsvReader.parse(csv, CsvFormat.defaults());
        return orchestrator.run(importer, table, mode, "skip", "widgets.csv", "admin");
    }

    private static final String TWO_ROWS = """
        key,name,price
        W-1,Плочки,10
        W-2,Боя,20
        """;

    // ── the steady state ──────────────────────────────────────────────────────

    @Test
    void createsUnknownKeys() {
        ImportReport r = apply(TWO_ROWS);

        assertThat(r.created()).isEqualTo(2);
        assertThat(r.status()).isEqualTo(ImportReport.OK);
        assertThat(importer.store).hasSize(2);
        assertThat(refs).containsOnlyKeys("W-1", "W-2");
    }

    @Test
    void runningTheSameFileTwiceChangesNothingTheSecondTime() {
        apply(TWO_ROWS);
        int writesAfterFirstRun = importer.writes;

        ImportReport second = apply(TWO_ROWS);

        assertThat(second.unchanged()).isEqualTo(2);
        assertThat(second.created()).isZero();
        assertThat(second.updated()).isZero();
        assertThat(importer.writes).isEqualTo(writesAfterFirstRun);   // not one extra write
    }

    @Test
    void validateModeReportsWhatWouldHappenButWritesNothing() {
        ImportReport r = run(TWO_ROWS, ImportOrchestrator.MODE_VALIDATE);

        assertThat(r.created()).isEqualTo(2);
        assertThat(r.mode()).isEqualTo(ImportOrchestrator.MODE_VALIDATE);
        assertThat(importer.store).isEmpty();
        assertThat(refs).isEmpty();
    }

    // ── the four merge quadrants, through the whole pipeline ──────────────────

    @Test
    void aChangeInTheSheetIsApplied() {
        apply(TWO_ROWS);

        ImportReport r = apply("""
            key,name,price
            W-1,Плочки,15
            W-2,Боя,20
            """);

        assertThat(r.updated()).isEqualTo(1);
        assertThat(r.unchanged()).isEqualTo(1);
        assertThat(importer.store.get(1L)).containsEntry("price", "15");
    }

    @Test
    void aChangeInTheAppSurvivesAStaleSheet() {
        apply(TWO_ROWS);
        importer.store.get(1L).put("price", "99");     // someone edited it in the app

        ImportReport r = apply(TWO_ROWS);

        assertThat(r.keptApp()).isEqualTo(1);
        assertThat(importer.store.get(1L)).containsEntry("price", "99");
    }

    @Test
    void aStaleSheetValueIsNotReReportedOnEveryRun() {
        // The re-base after KEPT_APP is what stops this from becoming a phantom conflict later.
        apply(TWO_ROWS);
        importer.store.get(1L).put("price", "99");
        apply(TWO_ROWS);

        ImportReport third = apply(TWO_ROWS);

        assertThat(third.keptApp()).isZero();
        assertThat(third.unchanged()).isEqualTo(2);
        assertThat(third.conflicts()).isZero();
    }

    @Test
    void bothSidesMovingIsAConflictAndNothingIsWritten() {
        apply(TWO_ROWS);
        importer.store.get(1L).put("price", "99");     // app moved

        ImportReport r = apply("""
            key,name,price
            W-1,Плочки,15
            W-2,Боя,20
            """);                                       // sheet moved too

        assertThat(r.conflicts()).isEqualTo(1);
        assertThat(r.status()).isEqualTo(ImportReport.PARTIAL);
        assertThat(importer.store.get(1L)).containsEntry("price", "99");   // untouched
        assertThat(savedConflicts).hasSize(1);
        assertThat(savedConflicts.get(0).getColumnName()).isEqualTo("price");
        assertThat(savedConflicts.get(0).getAppValue()).isEqualTo("99");
        assertThat(savedConflicts.get(0).getSheetValue()).isEqualTo("15");
        assertThat(r.conflictDetails().get(0).key()).isEqualTo("W-1");
    }

    // ── failure handling ──────────────────────────────────────────────────────

    @Test
    void duplicateKeysAreReportedNotSilentlyLastWins() {
        ImportReport r = apply("""
            key,name,price
            W-1,Плочки,10
            W-1,Боя,20
            """);

        assertThat(r.created()).isEqualTo(1);
        assertThat(r.failed()).isEqualTo(1);
        assertThat(r.errors().get(0).code()).isEqualTo(ImportErrorCode.DUPLICATE_KEY);
        assertThat(r.errors().get(0).line()).isEqualTo(3);
    }

    @Test
    void oneBadRowDoesNotStopTheOthers() {
        ImportReport r = apply("""
            key,name,price
            W-1,Плочки,10
            W-2,,20
            W-3,Боя,30
            """);

        assertThat(r.created()).isEqualTo(2);
        assertThat(r.failed()).isEqualTo(1);
        assertThat(r.errors().get(0).code()).isEqualTo(ImportErrorCode.EMPTY_REQUIRED);
        assertThat(r.status()).isEqualTo(ImportReport.PARTIAL);
    }

    @Test
    void aMissingRequiredColumnFailsTheWholeFileUpFront() {
        assertThatThrownBy(() -> apply("key,price\nW-1,10\n"))
            .isInstanceOf(CsvException.class)
            .hasMessageContaining("name");
    }

    @Test
    void unknownColumnsAreWarnedAboutAndIgnored() {
        ImportReport r = apply("key,name,price,коментар\nW-1,Плочки,10,бележка\n");

        assertThat(r.created()).isEqualTo(1);
        assertThat(r.warnings()).hasSize(1);
        assertThat(r.warnings().get(0).code()).isEqualTo(ImportErrorCode.UNKNOWN_COLUMN);
        assertThat(r.warnings().get(0).column()).isEqualTo("коментар");
    }

    @Test
    void aMappingLeftDanglingByAUiDeleteIsRecreated() {
        apply(TWO_ROWS);
        importer.store.remove(1L);                      // deleted through the app

        ImportReport r = apply(TWO_ROWS);

        assertThat(r.created()).isEqualTo(1);
        assertThat(r.warnings()).anySatisfy(w ->
            assertThat(w.message()).contains("re-created"));
        // The key now points at the new row, not the deleted id.
        assertThat(refs.get("W-1").getEntityId()).isEqualTo(3L);
    }

    // ── an entity store standing in for the real ones ─────────────────────────

    /** Deliberately a fake rather than a mock: the assertions are about the resulting data. */
    private static final class FakeImporter implements EntityImporter {

        final Map<Long, Map<String, String>> store = new LinkedHashMap<>();
        final AtomicLong ids = new AtomicLong();
        int writes;

        @Override public String name()       { return "widgets"; }
        @Override public String entityType() { return "widget"; }

        @Override public Map<String, ColumnType> columns() {
            return Map.of("name", ColumnType.TEXT, "price", ColumnType.DECIMAL);
        }

        @Override public Set<String> requiredColumns() { return Set.of("name"); }

        @Override public Map<String, String> readRow(CsvRow row) {
            Map<String, String> v = new LinkedHashMap<>();
            if (row.has("name")) {
                row.requiredText("name");
                v.put("name", ValueNormalizer.normalize(row.text("name"), ColumnType.TEXT));
            }
            if (row.has("price")) {
                v.put("price", ValueNormalizer.normalize(row.decimal("price"), ColumnType.DECIMAL));
            }
            return v;
        }

        @Override public Map<String, String> project(Long entityId) {
            Map<String, String> v = store.get(entityId);
            return v == null ? null : new LinkedHashMap<>(v);
        }

        @Override public Long create(Map<String, String> values) {
            writes++;
            Long id = ids.incrementAndGet();
            store.put(id, new LinkedHashMap<>(values));
            return id;
        }

        @Override public void update(Long entityId, Map<String, String> values) {
            writes++;
            store.get(entityId).putAll(values);
        }
    }
}
