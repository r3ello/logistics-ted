package com.bellgado.logistics_ted.service.importer.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bellgado.logistics_ted.domain.House;
import com.bellgado.logistics_ted.domain.HouseStage;
import com.bellgado.logistics_ted.repository.HouseRepository;
import com.bellgado.logistics_ted.repository.HouseStageRepository;
import com.bellgado.logistics_ted.service.importer.ImportErrorCode;
import com.bellgado.logistics_ted.web.importer.csv.CsvException;
import com.bellgado.logistics_ted.web.importer.csv.CsvFormat;
import com.bellgado.logistics_ted.web.importer.csv.CsvReader;
import com.bellgado.logistics_ted.web.importer.csv.CsvTable;
import com.bellgado.logistics_ted.web.importer.csv.CsvValueException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The stage importer against the shape of the real ACTIVE_MASTER header: numbered stage columns
 * whose numbers do not match our stage_order, one spelling difference, and house columns that
 * belong to the other importer.
 */
class HouseStageImporterTest {

    /** A slice of stage_type: [stage_order, stage_name, stage_name_en], in display order. */
    private static final List<Object[]> TYPES = List.of(
        new Object[]{1,  "Фундамент",    "Foundation"},
        new Object[]{2,  "Конструкция",  "Structure"},
        new Object[]{8,  "Ниво замазка", "Screed Level"},
        new Object[]{11, "Ел",           "Electrical"},
        new Object[]{26, "Първази",      "Skirting Boards"},
        new Object[]{27, "Приключване",  "Completion"});

    private final House house = new House();
    private final Map<Integer, HouseStage> rows = new HashMap<>();
    private HouseStageImporter importer;

    @BeforeEach
    void setUp() {
        house.setId(56);
        house.setName("Широки дол Теодор");
        house.setExternalId("TH-2025-012");
        for (Object[] t : TYPES) {
            HouseStage s = new HouseStage();
            s.setHouse(house);
            s.setStageOrder((Integer) t[0]);
            s.setStageName((String) t[1]);
            rows.put(s.getStageOrder(), s);
        }

        HouseRepository houses = mock(HouseRepository.class);
        when(houses.findByExternalId(any())).thenAnswer(inv ->
            "TH-2025-012".equals(inv.getArgument(0)) ? Optional.of(house) : Optional.empty());
        when(houses.findById(56)).thenReturn(Optional.of(house));
        when(houses.existsById(56)).thenReturn(true);
        when(houses.save(any(House.class))).thenAnswer(inv -> inv.getArgument(0));

        HouseStageRepository stages = mock(HouseStageRepository.class);
        when(stages.findDistinctStageTypes()).thenReturn(TYPES);
        when(stages.findByHouseIdAndStageOrder(anyInt(), anyInt()))
            .thenAnswer(inv -> Optional.ofNullable(rows.get((Integer) inv.getArgument(1))));
        when(stages.findByHouseIdInDisplayOrder(56)).thenAnswer(inv -> new ArrayList<>(rows.values()));
        when(stages.save(any(HouseStage.class))).thenAnswer(inv -> {
            HouseStage s = inv.getArgument(0);
            rows.put(s.getStageOrder(), s);
            return s;
        });
        when(stages.findAllInProgressStageNames(56)).thenAnswer(inv -> rows.values().stream()
            .filter(s -> "IN_PROGRESS".equals(s.getStatus())).map(HouseStage::getStageName).toList());

        importer = new HouseStageImporter(houses, stages);
    }

    /** The client's file as exported, run through the importer's header resolver like the orchestrator does. */
    private CsvTable sheet(String header, String data) {
        return CsvReader.parse(header + "\n" + data + "\n", CsvFormat.defaults())
            .renameHeaders(importer.headerResolver());
    }

    private static final String MASTER_HEADER =
        "Project_ID,,Project_Name,1_Конструкция,7_Ниво замазка,10_Ел,25_Первази,26_Приключване,Location";

    @Test
    void stageColumnsAreMatchedByNameNotNumber() {
        CsvTable t = sheet(MASTER_HEADER, "TH-2025-012,📸,Широки дол Теодор,,,,,,");
        // project_name / location belong to the houses importer: left as unknown columns.
        assertThat(t.headers()).containsExactly(
            "key", "project_name", "конструкция", "ниво замазка", "ел", "първази", "приключване", "location");
        assertThat(t.unknownColumns(new java.util.HashSet<>(importer.columns().keySet())))
            .containsExactly("key", "project_name", "location");   // key is added by the orchestrator
    }

    @Test
    void conflictColumnsAreDescribedByTheSheetsStageName() {
        assertThat(importer.describeColumn("stage_8_status")).isEqualTo("Stage 'Ниво замазка' (status)");
        assertThat(importer.describeColumn("stage_99_note")).isEqualTo("Stage '#99' (note)");
    }

    @Test
    void englishNamesAreAcceptedToo() {
        assertThat(importer.headerResolver().apply("3_structure")).isEqualTo("конструкция");
    }

    @Test
    void cellsBecomePerStageFieldsAndNeIsLeftOut() {
        CsvTable t = sheet(MASTER_HEADER,
            "TH-2025-012,📸,Широки дол Теодор,Влади Завършен,Междинен,Ради в Процес,Не,,https://maps.app.goo.gl/x");
        Map<String, String> v = importer.readRow(t.rows().get(0));
        assertThat(v)
            .containsEntry("stage_2_status", "DONE").containsEntry("stage_2_worker", "Влади")
            .containsEntry("stage_8_note", "Междинен")
            .containsEntry("stage_11_status", "IN_PROGRESS").containsEntry("stage_11_worker", "Ради")
            .containsEntry("stage_27_status", "NOT_STARTED");
        assertThat(v.get("stage_27_worker")).isNull();
        assertThat(v).containsKey("stage_27_worker");                 // empty cell clears the worker
        assertThat(v).doesNotContainKeys("stage_8_status", "stage_8_worker",   // note-only cell
                                         "stage_26_status", "stage_26_worker", "stage_26_note");  // Не
        // Фундамент is not in the sheet, but Конструкция is done: the foundation is inferred.
        assertThat(v).containsEntry("stage_1_status", "DONE").doesNotContainKey("stage_1_worker");
    }

    @Test
    void theFoundationIsLeftAloneWhileNothingHasStarted() {
        CsvTable t = sheet(MASTER_HEADER, "TH-2025-012,,Широки дол Теодор,Жоро,Междинен,,Не,,");
        assertThat(importer.readRow(t.rows().get(0))).doesNotContainKey("stage_1_status");  // ASSIGNED is not started
    }

    @Test
    void aFoundationColumnInTheSheetIsReadLikeAnyOtherStage() {
        String header = "Project_ID,0_Фундамент,1_Конструкция";
        assertThat(importer.readRow(sheet(header, "TH-2025-012,,Влади Завършен").rows().get(0)))
            .containsEntry("stage_1_status", "NOT_STARTED");             // the sheet says so: no inference
        assertThat(importer.readRow(sheet(header, "TH-2025-012,Стоян Завършен,").rows().get(0)))
            .containsEntry("stage_1_status", "DONE").containsEntry("stage_1_worker", "Стоян");
    }

    @Test
    void aRowForAHouseThatWasNotImportedFails() {
        CsvTable t = sheet(MASTER_HEADER, "TH-9999-001,,Нищо,,,,,,");
        assertThatThrownBy(() -> importer.readRow(t.rows().get(0)))
            .isInstanceOf(CsvValueException.class)
            .satisfies(e -> assertThat(((CsvValueException) e).getCode()).isEqualTo(ImportErrorCode.UNRESOLVED_REF));
    }

    @Test
    void updateWritesOnlyTheGivenFieldsAndRecomputesThePhase() {
        HouseStage screed = rows.get(8);
        screed.setStatus("IN_PROGRESS");
        screed.setWorkerName("Сашо");
        HouseStage done = rows.get(26);
        done.setStatus("DONE");
        done.setStartDate(LocalDate.of(2026, 5, 1));
        done.setEndDate(LocalDate.of(2026, 5, 9));

        Map<String, String> apply = new HashMap<>();
        apply.put("stage_2_status", "DONE");
        apply.put("stage_2_worker", "Влади");
        apply.put("stage_8_note", "Междинен");
        apply.put("stage_11_status", "IN_PROGRESS");
        apply.put("stage_26_status", "NOT_STARTED");
        apply.put("stage_26_worker", null);
        importer.update(56L, apply);

        assertThat(rows.get(2).getStatus()).isEqualTo("DONE");
        assertThat(rows.get(2).getWorkerName()).isEqualTo("Влади");
        assertThat(rows.get(2).getEndDate()).isNull();                 // no invented dates
        assertThat(screed.getStatus()).isEqualTo("IN_PROGRESS");       // note-only: status untouched
        assertThat(screed.getWorkerName()).isEqualTo("Сашо");
        assertThat(screed.getNotes()).isEqualTo("Междинен");
        assertThat(done.getStatus()).isEqualTo("NOT_STARTED");
        assertThat(done.getStartDate()).isNull();
        assertThat(done.getEndDate()).isNull();
        assertThat(house.getCurrentPhase()).contains("Ниво замазка").contains("Ел");
    }

    @Test
    void projectionUsesTheSameFieldKeys() {
        rows.get(11).setStatus("ASSIGNED");
        rows.get(11).setWorkerName("Петър");
        Map<String, String> p = importer.project(56L);
        assertThat(p).containsEntry("stage_11_status", "ASSIGNED").containsEntry("stage_11_worker", "Петър");
        assertThat(importer.project(999L)).isNull();
    }

    @Test
    void twoHeadersResolvingToOneStageAreRejected() {
        assertThatThrownBy(() -> sheet("Project_ID,1_Конструкция,Конструкция", "TH-2025-012,,"))
            .isInstanceOf(CsvException.class)
            .satisfies(e -> assertThat(((CsvException) e).getCode()).isEqualTo(ImportErrorCode.DUPLICATE_COLUMN));
    }
}
