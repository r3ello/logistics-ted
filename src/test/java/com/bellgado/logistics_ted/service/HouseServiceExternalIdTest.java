package com.bellgado.logistics_ted.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bellgado.logistics_ted.domain.House;
import com.bellgado.logistics_ted.domain.ImportRef;
import com.bellgado.logistics_ted.repository.DocFolderRepository;
import com.bellgado.logistics_ted.repository.HouseRepository;
import com.bellgado.logistics_ted.repository.HouseStageRepository;
import com.bellgado.logistics_ted.repository.ImportRefRepository;
import com.bellgado.logistics_ted.repository.InventoryRepository;
import com.bellgado.logistics_ted.repository.WarehouseRepository;
import com.bellgado.logistics_ted.storage.DocumentStorageService;
import com.bellgado.logistics_ted.web.dto.HouseUpsertRequest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * {@code house.external_id} and the CSV sync's {@code import_ref} must name the same key, or the next
 * import either duplicates the house or updates the wrong one. These pin the re-key on edit.
 */
class HouseServiceExternalIdTest {

    private final Map<Integer, House> stored = new HashMap<>();
    private final List<ImportRef> refs = new ArrayList<>();
    private HouseRepository houses;
    private ImportRefRepository importRefs;
    private HouseService service;

    @BeforeEach
    void setUp() {
        houses = mock(HouseRepository.class);
        when(houses.findById(any())).thenAnswer(inv -> Optional.ofNullable(stored.get(inv.<Integer>getArgument(0))));
        when(houses.existsById(any())).thenAnswer(inv -> stored.containsKey(inv.<Integer>getArgument(0)));
        when(houses.save(any(House.class))).thenAnswer(inv -> inv.getArgument(0));
        when(houses.findByExternalId(any())).thenAnswer(inv -> stored.values().stream()
            .filter(h -> inv.getArgument(0).equals(h.getExternalId())).findFirst());

        importRefs = mock(ImportRefRepository.class);
        when(importRefs.findByEntityTypeAndEntityId(eq("house"), anyLong())).thenAnswer(inv -> refs.stream()
            .filter(r -> r.getEntityId().equals(inv.getArgument(1))).toList());
        when(importRefs.findByEntityTypeAndExternalKey(eq("house"), any())).thenAnswer(inv -> refs.stream()
            .filter(r -> r.getExternalKey().equals(inv.getArgument(1))).findFirst());

        service = new HouseService(houses, mock(WarehouseRepository.class), mock(InventoryRepository.class),
            mock(HouseStageRepository.class), mock(DocFolderRepository.class),
            mock(HouseTemplateFolderService.class), mock(DocumentStorageService.class),
            mock(ApplicationEventPublisher.class), importRefs);
    }

    private House house(int id, String externalId) {
        House h = new House();
        h.setId(id);
        h.setName("Къща " + id);
        h.setAddress("Рударци");
        h.setExternalId(externalId);
        stored.put(id, h);
        return h;
    }

    private ImportRef ref(String key, long entityId) {
        ImportRef r = new ImportRef();
        r.setEntityType("house");
        r.setExternalKey(key);
        r.setEntityId(entityId);
        refs.add(r);
        return r;
    }

    private static HouseUpsertRequest edit(String externalId) {
        return new HouseUpsertRequest("Къща", "Рударци", null, null, null, null, null, null, null, null,
            null, externalId);
    }

    @Test
    void editingTheExternalIdReKeysTheImportMapping() {
        house(1, "CRM-1");
        ImportRef r = ref("CRM-1", 1);

        service.update(1, edit("  CRM-1b "));

        assertThat(stored.get(1).getExternalId()).isEqualTo("CRM-1b");
        assertThat(r.getExternalKey()).isEqualTo("CRM-1b");
        verify(importRefs).save(r);
    }

    @Test
    void clearingTheExternalIdUnlinksTheHouseFromTheSync() {
        house(1, "CRM-1");
        ImportRef r = ref("CRM-1", 1);

        service.update(1, edit(""));

        assertThat(stored.get(1).getExternalId()).isNull();
        verify(importRefs).deleteAll(List.of(r));
    }

    @Test
    void omittingTheExternalIdLeavesItAlone() {
        house(1, "CRM-1");
        ref("CRM-1", 1);

        service.update(1, edit(null));

        assertThat(stored.get(1).getExternalId()).isEqualTo("CRM-1");
        verify(importRefs, never()).save(any());
    }

    @Test
    void anExternalIdAlreadyUsedByAnotherHouseIsRejected() {
        house(1, "CRM-1");
        house(2, "CRM-2");

        assertThatThrownBy(() -> service.update(2, edit("CRM-1")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("house #1");
        assertThat(stored.get(2).getExternalId()).isEqualTo("CRM-2");
    }

    @Test
    void aDanglingMappingForTheNewIdIsDroppedSoTheReKeyCanTakeIt() {
        house(1, "CRM-1");
        ImportRef own = ref("CRM-1", 1);
        ImportRef dangling = ref("CRM-9", 99);          // house 99 was deleted

        service.update(1, edit("CRM-9"));

        verify(importRefs).delete(dangling);
        verify(importRefs).flush();
        assertThat(own.getExternalKey()).isEqualTo("CRM-9");
    }

    @Test
    void createRejectsADuplicateExternalId() {
        house(1, "CRM-1");

        assertThatThrownBy(() -> service.create(edit("CRM-1")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("CRM-1");
    }
}
