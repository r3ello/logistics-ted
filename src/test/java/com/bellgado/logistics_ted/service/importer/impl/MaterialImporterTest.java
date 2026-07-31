package com.bellgado.logistics_ted.service.importer.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bellgado.logistics_ted.domain.Material;
import com.bellgado.logistics_ted.repository.MaterialRepository;
import com.bellgado.logistics_ted.service.importer.ImportErrorCode;
import com.bellgado.logistics_ted.web.importer.csv.CsvFormat;
import com.bellgado.logistics_ted.web.importer.csv.CsvReader;
import com.bellgado.logistics_ted.web.importer.csv.CsvRow;
import com.bellgado.logistics_ted.web.importer.csv.CsvValueException;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The first concrete importer, and the template the rest are written against. */
class MaterialImporterTest {

    private final Map<Integer, Material> stored = new HashMap<>();
    private final AtomicInteger ids = new AtomicInteger();
    private MaterialImporter importer;

    @BeforeEach
    void setUp() {
        MaterialRepository repo = mock(MaterialRepository.class);
        when(repo.save(any(Material.class))).thenAnswer(inv -> {
            Material m = inv.getArgument(0);
            if (m.getId() == null) m.setId(ids.incrementAndGet());
            stored.put(m.getId(), m);
            return m;
        });
        when(repo.findById(any())).thenAnswer(inv -> Optional.ofNullable(stored.get(inv.getArgument(0))));
        importer = new MaterialImporter(repo);
    }

    private static CsvRow row(String header, String data) {
        return CsvReader.parse(header + "\n" + data + "\n", CsvFormat.defaults()).rows().get(0);
    }

    @Test
    void readsAndCanonicalisesARow() {
        Map<String, String> v = importer.readRow(row("key,name,unit,price", "MAT-001,ОСБ плоскост,m2,24.50"));

        assertThat(v).containsOnlyKeys("name", "unit", "price");
        assertThat(v.get("name")).isEqualTo("ОСБ плоскост");
        // Canonical form, so 24.50 from the sheet and 24.5000 from the DB compare equal.
        assertThat(v.get("price")).isEqualTo("24.5");
    }

    @Test
    void columnsAbsentFromTheHeaderAreNotManaged() {
        // A file without a price column must leave prices alone, not reset them.
        Map<String, String> v = importer.readRow(row("key,name,unit", "MAT-001,ОСБ,m2"));
        assertThat(v).containsOnlyKeys("name", "unit");
    }

    @Test
    void requiredColumnsAreEnforcedPerRow() {
        assertThatThrownBy(() -> importer.readRow(row("key,name,unit", "MAT-001,,m2")))
            .isInstanceOf(CsvValueException.class)
            .satisfies(e -> assertThat(((CsvValueException) e).getCode()).isEqualTo(ImportErrorCode.EMPTY_REQUIRED));
    }

    @Test
    void lengthLimitsMirrorTheColumnDefinition() {
        String longName = "щ".repeat(101);   // material.name is varchar(100)
        assertThatThrownBy(() -> importer.readRow(row("key,name,unit", "MAT-001," + longName + ",m2")))
            .isInstanceOf(CsvValueException.class)
            .satisfies(e -> assertThat(((CsvValueException) e).getCode()).isEqualTo(ImportErrorCode.TOO_LONG));
    }

    @Test
    void createsWithZeroPriceWhenTheFileHasNoPriceColumn() {
        Long id = importer.create(importer.readRow(row("key,name,unit", "MAT-001,ОСБ,m2")));

        // price is NOT NULL in the schema, so a file that does not manage it still has to be valid.
        assertThat(stored.get(id.intValue()).getPrice()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(stored.get(id.intValue()).getName()).isEqualTo("ОСБ");
    }

    @Test
    void updateAppliesOnlyTheGivenColumns() {
        Long id = importer.create(importer.readRow(row("key,name,unit,price", "MAT-001,ОСБ,m2,24.50")));

        importer.update(id, Map.of("price", "26"));

        Material m = stored.get(id.intValue());
        assertThat(m.getPrice()).isEqualByComparingTo("26");
        assertThat(m.getName()).isEqualTo("ОСБ");     // untouched
        assertThat(m.getUnit()).isEqualTo("m2");
    }

    @Test
    void projectionRoundTripsThroughTheSameCanonicalForm() {
        Long id = importer.create(importer.readRow(row("key,name,unit,price", "MAT-001,ОСБ,m2,24.50")));
        // The DB stores numeric(10,2); the projection must still compare equal to the sheet value,
        // otherwise every run would report a phantom price change.
        stored.get(id.intValue()).setPrice(new BigDecimal("24.5000"));

        assertThat(importer.project(id))
            .containsEntry("price", "24.5")
            .containsEntry("name", "ОСБ")
            .containsEntry("unit", "m2");
    }

    @Test
    void projectionIsNullWhenTheEntityIsGone() {
        // How a mapping left dangling by a UI delete is detected.
        assertThat(importer.project(999L)).isNull();
    }
}
