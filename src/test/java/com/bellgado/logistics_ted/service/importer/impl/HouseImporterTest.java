package com.bellgado.logistics_ted.service.importer.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bellgado.logistics_ted.domain.House;
import com.bellgado.logistics_ted.domain.ScaffoldStatus;
import com.bellgado.logistics_ted.repository.HouseRepository;
import com.bellgado.logistics_ted.service.HouseService;
import com.bellgado.logistics_ted.service.importer.ImportErrorCode;
import com.bellgado.logistics_ted.web.dto.HouseResponse;
import com.bellgado.logistics_ted.web.dto.HouseUpsertRequest;
import com.bellgado.logistics_ted.web.importer.csv.CsvFormat;
import com.bellgado.logistics_ted.web.importer.csv.CsvReader;
import com.bellgado.logistics_ted.web.importer.csv.CsvRow;
import com.bellgado.logistics_ted.web.importer.csv.CsvValueException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The first importer whose create path is a service, not a repository — the mock
 * {@code HouseService} stands in for the warehouse + 27 stages + token + folders side effects that
 * make the delegation mandatory (DATA_IMPORT_PLAN.md §0.2).
 *
 * <p>Since Flyway V8 the column names follow the CRM's vocabulary: {@code address} is the required
 * address text, {@code location} the optional Google Maps link.
 */
class HouseImporterTest {

    private final Map<Integer, House> stored = new HashMap<>();
    private final AtomicInteger ids = new AtomicInteger();
    private HouseService service;
    private HouseImporter importer;

    @BeforeEach
    void setUp() {
        HouseRepository repo = mock(HouseRepository.class);
        when(repo.save(any(House.class))).thenAnswer(inv -> {
            House h = inv.getArgument(0);
            if (h.getId() == null) h.setId(ids.incrementAndGet());
            stored.put(h.getId(), h);
            return h;
        });
        when(repo.findById(any())).thenAnswer(inv -> Optional.ofNullable(stored.get(inv.getArgument(0))));

        // The service mock mimics only what the importer consumes: create() persisting the house
        // fields and returning the new id. The real side effects are exactly why it must be called.
        service = mock(HouseService.class);
        when(service.create(any(HouseUpsertRequest.class))).thenAnswer(inv -> {
            HouseUpsertRequest req = inv.getArgument(0);
            House h = new House();
            h.setId(ids.incrementAndGet());
            h.setName(req.name());
            h.setAddress(req.address());
            h.setLocation(req.location());
            h.setLat(req.lat());
            h.setLng(req.lng());
            h.setStartDate(req.startDate() == null ? null : LocalDate.parse(req.startDate()));
            if (req.scaffoldStatus() != null) h.setScaffoldStatus(req.scaffoldStatus());
            h.setScaffoldStartDate(req.scaffoldStartDate() == null ? null : LocalDate.parse(req.scaffoldStartDate()));
            h.setScaffoldEndDate(req.scaffoldEndDate() == null ? null : LocalDate.parse(req.scaffoldEndDate()));
            stored.put(h.getId(), h);
            return new HouseResponse(h.getId(), h.getName(), h.getAddress(), h.getLocation(),
                h.getLat(), h.getLng(), req.startDate(), null);
        });

        importer = new HouseImporter(service, repo);
    }

    private static CsvRow row(String header, String data) {
        return CsvReader.parse(header + "\n" + data + "\n", CsvFormat.defaults()).rows().get(0);
    }

    private static final String FULL_HEADER =
        "key,name,address,location,lat,lng,start_date,scaffold_status,scaffold_start_date,scaffold_end_date";

    private static final String MAPS_LINK = "https://maps.app.goo.gl/nRDmHHrUMxXvW9FXA?g_st=iv";

    @Test
    void readsAndCanonicalisesARow() {
        Map<String, String> v = importer.readRow(row(FULL_HEADER,
            "H-001,Къща Иванов,\"с. Бистрица, ул. Витоша 12\"," + MAPS_LINK
                + ",42.601234,23.345678,04.03.2026,available,2026-03-10,"));

        assertThat(v).containsOnlyKeys("name", "address", "location", "lat", "lng", "start_date",
            "scaffold_status", "scaffold_start_date", "scaffold_end_date");
        assertThat(v.get("name")).isEqualTo("Къща Иванов");
        assertThat(v.get("address")).isEqualTo("с. Бистрица, ул. Витоша 12");
        assertThat(v.get("location")).isEqualTo(MAPS_LINK);
        assertThat(v.get("lat")).isEqualTo("42.601234");
        // dd.MM.yyyy and lower-case enum both canonicalise, so sheet and DB compare equal.
        assertThat(v.get("start_date")).isEqualTo("2026-03-04");
        assertThat(v.get("scaffold_status")).isEqualTo("AVAILABLE");
        assertThat(v.get("scaffold_end_date")).isNull();      // empty cell in a present column = null value
    }

    @Test
    void columnsAbsentFromTheHeaderAreNotManaged() {
        // The client's sheet manages only name + address; coords set in the map picker must survive.
        Map<String, String> v = importer.readRow(row("key,name,address", "CRM-2026-00010,Рударци Йордан,Рударци"));
        assertThat(v).containsOnlyKeys("name", "address");
    }

    @Test
    void requiredColumnsAreEnforcedPerRow() {
        assertThatThrownBy(() -> importer.readRow(row("key,name,address", "H-001,,Рударци")))
            .isInstanceOf(CsvValueException.class)
            .satisfies(e -> assertThat(((CsvValueException) e).getCode()).isEqualTo(ImportErrorCode.EMPTY_REQUIRED));
        assertThatThrownBy(() -> importer.readRow(row("key,name,address", "H-001,Къща,")))
            .isInstanceOf(CsvValueException.class)
            .satisfies(e -> assertThat(((CsvValueException) e).getCode()).isEqualTo(ImportErrorCode.EMPTY_REQUIRED));
    }

    @Test
    void theMapsLinkIsOptionalAndStoredVerbatim() {
        // A house without a link must still import — the address is what the app needs. The link is
        // not parsed or validated: resolving it to coordinates would be a network call (§7).
        Map<String, String> blank = importer.readRow(row("key,name,address,location", "H-001,Къща,Рударци,"));
        assertThat(blank).containsEntry("location", null);

        Map<String, String> withLink =
            importer.readRow(row("key,name,address,location", "H-001,Къща,Рударци," + MAPS_LINK));
        assertThat(withLink).containsEntry("location", MAPS_LINK);
    }

    @Test
    void lengthLimitsMirrorTheColumnDefinition() {
        String longName = "щ".repeat(151);   // house.name is varchar(150)
        assertThatThrownBy(() -> importer.readRow(row("key,name,address", "H-001," + longName + ",Рударци")))
            .isInstanceOf(CsvValueException.class)
            .satisfies(e -> assertThat(((CsvValueException) e).getCode()).isEqualTo(ImportErrorCode.TOO_LONG));

        String longAddress = "у".repeat(256); // house.address is varchar(255)
        assertThatThrownBy(() -> importer.readRow(row("key,name,address", "H-001,Къща," + longAddress)))
            .isInstanceOf(CsvValueException.class)
            .satisfies(e -> assertThat(((CsvValueException) e).getCode()).isEqualTo(ImportErrorCode.TOO_LONG));

        String longLink = "https://maps.app.goo.gl/" + "x".repeat(500); // house.location is varchar(512)
        assertThatThrownBy(() ->
            importer.readRow(row("key,name,address,location", "H-001,Къща,Рударци," + longLink)))
            .isInstanceOf(CsvValueException.class)
            .satisfies(e -> assertThat(((CsvValueException) e).getCode()).isEqualTo(ImportErrorCode.TOO_LONG));
    }

    @Test
    void coordinatesAreRangeCheckedAndRounded() {
        assertThatThrownBy(() -> importer.readRow(row("key,name,address,lat", "H-001,Къща,Рударци,91")))
            .isInstanceOf(CsvValueException.class)
            .satisfies(e -> assertThat(((CsvValueException) e).getCode()).isEqualTo(ImportErrorCode.OUT_OF_RANGE));

        // numeric(9,6) — a 7th decimal must round here so the snapshot matches what Postgres stores.
        Map<String, String> v = importer.readRow(row("key,name,address,lat", "H-001,Къща,Рударци,42.6012345"));
        assertThat(v.get("lat")).isEqualTo("42.601235");
    }

    @Test
    void scaffoldStatusIsValidatedAgainstTheCheckConstraint() {
        assertThatThrownBy(() ->
            importer.readRow(row("key,name,address,scaffold_status", "H-001,Къща,Рударци,BROKEN")))
            .isInstanceOf(CsvValueException.class)
            .satisfies(e -> assertThat(((CsvValueException) e).getCode()).isEqualTo(ImportErrorCode.INVALID_ENUM));
    }

    @Test
    void createDelegatesToHouseService() {
        Long id = importer.create(importer.readRow(row(FULL_HEADER,
            "H-001,Къща Иванов,Бистрица," + MAPS_LINK
                + ",42.601234,23.345678,2026-03-04,AVAILABLE,2026-03-10,")));

        // Through the service — warehouse, stages, token and folders come with it. Never a raw insert.
        verify(service).create(any(HouseUpsertRequest.class));
        House h = stored.get(id.intValue());
        assertThat(h.getName()).isEqualTo("Къща Иванов");
        assertThat(h.getAddress()).isEqualTo("Бистрица");
        assertThat(h.getLocation()).isEqualTo(MAPS_LINK);
        assertThat(h.getScaffoldStatus()).isEqualTo(ScaffoldStatus.AVAILABLE);
        assertThat(h.getStartDate()).isEqualTo(LocalDate.of(2026, 3, 4));
    }

    @Test
    void updateAppliesOnlyTheGivenColumnsIncludingExplicitNulls() {
        Long id = importer.create(importer.readRow(row(FULL_HEADER,
            "H-001,Къща Иванов,Бистрица," + MAPS_LINK
                + ",42.601234,23.345678,2026-03-04,AVAILABLE,2026-03-10,")));

        Map<String, String> toApply = new HashMap<>();
        toApply.put("address", "с. Бистрица, ул. Витоша 12");
        toApply.put("location", null);                        // link cleared in the sheet
        toApply.put("lat", null);                             // empty cell clears the coordinate
        importer.update(id, toApply);

        House h = stored.get(id.intValue());
        assertThat(h.getAddress()).isEqualTo("с. Бистрица, ул. Витоша 12");
        assertThat(h.getLocation()).isNull();
        assertThat(h.getLat()).isNull();
        assertThat(h.getName()).isEqualTo("Къща Иванов");     // untouched
        assertThat(h.getLng()).isEqualByComparingTo("23.345678");
    }

    @Test
    void updateRenamingTheHouseAlsoRenamesItsDocFolder() {
        Long id = importer.create(importer.readRow(row("key,name,address", "H-001,Къща Иванов,Бистрица")));

        importer.update(id, Map.of("name", "Къща Петров"));

        assertThat(stored.get(id.intValue()).getName()).isEqualTo("Къща Петров");
        verify(service).syncHouseDocFolderName(stored.get(id.intValue()));
    }

    @Test
    void projectionRoundTripsThroughTheSameCanonicalForm() {
        Long id = importer.create(importer.readRow(row(FULL_HEADER,
            "H-001,Къща Иванов,Бистрица," + MAPS_LINK
                + ",42.601234,23.345678,2026-03-04,IN_USE,2026-03-10,2026-04-01")));
        // numeric(9,6) comes back with trailing zeros; the projection must still compare equal.
        stored.get(id.intValue()).setLat(new BigDecimal("42.601234000"));

        assertThat(importer.project(id))
            .containsEntry("name", "Къща Иванов")
            .containsEntry("address", "Бистрица")
            .containsEntry("location", MAPS_LINK)
            .containsEntry("lat", "42.601234")
            .containsEntry("start_date", "2026-03-04")
            .containsEntry("scaffold_status", "IN_USE")
            .containsEntry("scaffold_end_date", "2026-04-01");
    }

    @Test
    void projectionIsNullWhenTheEntityIsGone() {
        // How a mapping left dangling by a UI delete is detected.
        assertThat(importer.project(999L)).isNull();
    }
}
