package com.bellgado.logistics_ted.service.importer.impl;

import com.bellgado.logistics_ted.domain.Material;
import com.bellgado.logistics_ted.repository.MaterialRepository;
import com.bellgado.logistics_ted.service.importer.ColumnType;
import com.bellgado.logistics_ted.service.importer.EntityImporter;
import com.bellgado.logistics_ted.service.importer.ValueNormalizer;
import com.bellgado.logistics_ted.web.importer.csv.CsvRow;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The material catalogue — the simplest importer in the set, and the template for the rest.
 *
 * <p>It is also the only write path materials have: {@code MaterialController} is GET-only and no
 * admin CRUD exists anywhere, so unlike houses or workers there is no service to delegate to and the
 * repository is used directly (DATA_IMPORT_PLAN.md §6.6). Every other importer must go through its
 * service instead — creating a house through the repository would skip the warehouse, the 27
 * {@code house_stage} rows, the check-in token and the document folders.
 *
 * <p>{@code @Component} rather than {@code @Service} keeps it out of {@code ServiceLoggingAspect},
 * which logs arguments at DEBUG.
 */
@Component
public class MaterialImporter implements EntityImporter {

    /** Mirrors {@code material.name varchar(100)} / {@code unit varchar(50)}. */
    private static final int NAME_MAX = 100;
    private static final int UNIT_MAX = 50;

    private static final Map<String, ColumnType> COLUMNS = Map.of(
        "name",  ColumnType.TEXT,
        "unit",  ColumnType.TEXT,
        "price", ColumnType.DECIMAL);

    private final MaterialRepository materials;

    public MaterialImporter(MaterialRepository materials) {
        this.materials = materials;
    }

    @Override public String name()       { return "materials"; }
    @Override public String entityType() { return "material"; }

    @Override public Map<String, ColumnType> columns() { return COLUMNS; }

    @Override public Set<String> requiredColumns() { return Set.of("name", "unit"); }

    @Override
    public Map<String, String> readRow(CsvRow row) {
        Map<String, String> v = new LinkedHashMap<>();
        // Only columns the file actually declares are managed — an absent column must not be
        // compared, let alone nulled out.
        if (row.has("name")) {
            row.requiredText("name");   // EMPTY_REQUIRED, named at the row, not by a DB constraint
            v.put("name", ValueNormalizer.normalize(row.text("name", NAME_MAX), ColumnType.TEXT));
        }
        if (row.has("unit")) {
            row.requiredText("unit");
            v.put("unit", ValueNormalizer.normalize(row.text("unit", UNIT_MAX), ColumnType.TEXT));
        }
        if (row.has("price")) {
            BigDecimal price = row.decimal("price");
            v.put("price", ValueNormalizer.normalize(price, ColumnType.DECIMAL));
        }
        return v;
    }

    @Override
    public Map<String, String> project(Long entityId) {
        return materials.findById(entityId.intValue()).map(m -> {
            Map<String, String> v = new LinkedHashMap<>();
            v.put("name",  ValueNormalizer.normalize(m.getName(),  ColumnType.TEXT));
            v.put("unit",  ValueNormalizer.normalize(m.getUnit(),  ColumnType.TEXT));
            v.put("price", ValueNormalizer.normalize(m.getPrice(), ColumnType.DECIMAL));
            return v;
        }).orElse(null);
    }

    @Override
    public Long create(Map<String, String> values) {
        Material m = new Material();
        m.setPrice(BigDecimal.ZERO);        // NOT NULL DEFAULT 0.00 — a file without a price column is valid
        apply(m, values);
        return Long.valueOf(materials.save(m).getId());
    }

    @Override
    public void update(Long entityId, Map<String, String> values) {
        Material m = materials.findById(entityId.intValue()).orElseThrow(
            () -> new IllegalStateException("Material " + entityId + " disappeared mid-import"));
        apply(m, values);
        materials.save(m);
    }

    /** Applies only the columns present in {@code values} — the merge already decided which those are. */
    private void apply(Material m, Map<String, String> values) {
        if (values.containsKey("name"))  m.setName(values.get("name"));
        if (values.containsKey("unit"))  m.setUnit(values.get("unit"));
        if (values.containsKey("price")) {
            String p = values.get("price");
            m.setPrice(p == null ? BigDecimal.ZERO : new BigDecimal(p));
        }
    }

}
