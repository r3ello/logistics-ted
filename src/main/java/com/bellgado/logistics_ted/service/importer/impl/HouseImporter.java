package com.bellgado.logistics_ted.service.importer.impl;

import com.bellgado.logistics_ted.domain.House;
import com.bellgado.logistics_ted.domain.ScaffoldStatus;
import com.bellgado.logistics_ted.repository.HouseRepository;
import com.bellgado.logistics_ted.service.HouseService;
import com.bellgado.logistics_ted.service.importer.ColumnType;
import com.bellgado.logistics_ted.service.importer.EntityImporter;
import com.bellgado.logistics_ted.service.importer.ValueNormalizer;
import com.bellgado.logistics_ted.web.dto.HouseUpsertRequest;
import com.bellgado.logistics_ted.web.importer.csv.CsvRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Houses — the first importer that must NOT own its create path (DATA_IMPORT_PLAN.md §0.2).
 *
 * <p>{@link #create} delegates to {@link HouseService#create}, because a house is five rows, not
 * one: the 1:1 warehouse, 27 {@code house_stage} rows, the check-in QR token, the doc folder under
 * ACTIVE_SITES and its template tree. A repository insert here — copying {@code MaterialImporter},
 * which is the documented exception, not the pattern — would produce houses with no stock, no stage
 * matrix and no attendance check-in.
 *
 * <p>{@link #update} instead writes the entity directly. The merge hands over exactly the columns it
 * decided to apply, including explicit nulls (an empty cell clears a coordinate or a date), and
 * {@code HouseService.update}'s null-means-untouched {@code applyFields} cannot express that. The one
 * update side effect that matters — the doc folder renaming with the house — is preserved via
 * {@link HouseService#syncHouseDocFolderName}.
 *
 * <p>Column naming follows the CRM's own vocabulary (Flyway V8): {@code address} is the address text
 * (its {@code Address}) and {@code location} is the Google Maps link (its {@code Location}). The link
 * is stored verbatim and never resolved — turning a short link into coordinates means following an
 * HTTP redirect, which must not happen inside an import (§7); {@code lat}/{@code lng} keep coming
 * from the map picker.
 *
 * <p>{@code current_phase} is deliberately not a column: the app derives it from {@code house_stage}
 * and the sync must not fight it (§3.2). The client's CRM export carries ~24 further columns
 * (client name, prices, links, ЕГН…) with no target here — the orchestrator ignores them with
 * {@code UNKNOWN_COLUMN} warnings, and the personal data among them must never gain a mapping.
 *
 * <p>{@code @Component} rather than {@code @Service} keeps it out of {@code ServiceLoggingAspect},
 * which logs arguments at DEBUG.
 */
@Component
public class HouseImporter implements EntityImporter {

    /** Mirrors {@code house.name varchar(150)} / {@code address varchar(255)} / {@code location varchar(512)}. */
    private static final int NAME_MAX = 150;
    private static final int ADDRESS_MAX = 255;
    private static final int LOCATION_MAX = 512;

    private static final Set<String> SCAFFOLD_STATUSES =
        Set.of(ScaffoldStatus.NONE.name(), ScaffoldStatus.AVAILABLE.name(), ScaffoldStatus.IN_USE.name());

    private static final Map<String, ColumnType> COLUMNS = Map.of(
        "name",                ColumnType.TEXT,
        "address",             ColumnType.TEXT,
        "location",            ColumnType.TEXT,
        "lat",                 ColumnType.DECIMAL,
        "lng",                 ColumnType.DECIMAL,
        "start_date",          ColumnType.DATE,
        "scaffold_status",     ColumnType.ENUM,
        "scaffold_start_date", ColumnType.DATE,
        "scaffold_end_date",   ColumnType.DATE);

    private final HouseService houseService;
    private final HouseRepository houses;

    public HouseImporter(HouseService houseService, HouseRepository houses) {
        this.houseService = houseService;
        this.houses = houses;
    }

    @Override public String name()       { return "houses"; }
    @Override public String entityType() { return "house"; }

    @Override public Map<String, ColumnType> columns() { return COLUMNS; }

    @Override public Set<String> requiredColumns() { return Set.of("name", "address"); }

    @Override
    public Map<String, String> readRow(CsvRow row) {
        Map<String, String> v = new LinkedHashMap<>();
        if (row.has("name")) {
            row.requiredText("name");
            v.put("name", ValueNormalizer.normalize(row.text("name", NAME_MAX), ColumnType.TEXT));
        }
        if (row.has("address")) {
            row.requiredText("address");
            v.put("address", ValueNormalizer.normalize(row.text("address", ADDRESS_MAX), ColumnType.TEXT));
        }
        // The CRM's `Location` — a Google Maps link, optional and stored verbatim. Not validated as a
        // URL: the client's sheet is the authority on it, and a rejected row would block the address.
        if (row.has("location")) {
            v.put("location", ValueNormalizer.normalize(row.text("location", LOCATION_MAX), ColumnType.TEXT));
        }
        if (row.has("lat")) {
            v.put("lat", ValueNormalizer.normalize(round6(row.decimal("lat", -90, 90)), ColumnType.DECIMAL));
        }
        if (row.has("lng")) {
            v.put("lng", ValueNormalizer.normalize(round6(row.decimal("lng", -180, 180)), ColumnType.DECIMAL));
        }
        if (row.has("start_date")) {
            v.put("start_date", ValueNormalizer.normalize(row.date("start_date"), ColumnType.DATE));
        }
        if (row.has("scaffold_status")) {
            v.put("scaffold_status", ValueNormalizer.normalize(
                row.enumOf("scaffold_status", SCAFFOLD_STATUSES), ColumnType.ENUM));
        }
        if (row.has("scaffold_start_date")) {
            v.put("scaffold_start_date", ValueNormalizer.normalize(row.date("scaffold_start_date"), ColumnType.DATE));
        }
        if (row.has("scaffold_end_date")) {
            v.put("scaffold_end_date", ValueNormalizer.normalize(row.date("scaffold_end_date"), ColumnType.DATE));
        }
        return v;
    }

    @Override
    public Map<String, String> project(Long entityId) {
        return houses.findById(entityId.intValue()).map(h -> {
            Map<String, String> v = new LinkedHashMap<>();
            v.put("name",                ValueNormalizer.normalize(h.getName(),              ColumnType.TEXT));
            v.put("address",             ValueNormalizer.normalize(h.getAddress(),           ColumnType.TEXT));
            v.put("location",            ValueNormalizer.normalize(h.getLocation(),          ColumnType.TEXT));
            v.put("lat",                 ValueNormalizer.normalize(h.getLat(),               ColumnType.DECIMAL));
            v.put("lng",                 ValueNormalizer.normalize(h.getLng(),               ColumnType.DECIMAL));
            v.put("start_date",          ValueNormalizer.normalize(h.getStartDate(),         ColumnType.DATE));
            v.put("scaffold_status",     ValueNormalizer.normalize(h.getScaffoldStatus(),    ColumnType.ENUM));
            v.put("scaffold_start_date", ValueNormalizer.normalize(h.getScaffoldStartDate(), ColumnType.DATE));
            v.put("scaffold_end_date",   ValueNormalizer.normalize(h.getScaffoldEndDate(),   ColumnType.DATE));
            return v;
        }).orElse(null);
    }

    @Override
    public Long create(Map<String, String> values) {
        HouseUpsertRequest req = new HouseUpsertRequest(
            values.get("name"),
            values.get("address"),
            values.get("location"),
            decimal(values.get("lat")),
            decimal(values.get("lng")),
            values.get("start_date"),
            null,                                       // current_phase — derived, never imported
            scaffoldStatus(values.get("scaffold_status")),
            values.get("scaffold_start_date"),
            values.get("scaffold_end_date"));
        return Long.valueOf(houseService.create(req).id());
    }

    @Override
    public void update(Long entityId, Map<String, String> values) {
        House h = houses.findById(entityId.intValue()).orElseThrow(
            () -> new IllegalStateException("House " + entityId + " disappeared mid-import"));
        if (values.containsKey("name"))     h.setName(values.get("name"));
        if (values.containsKey("address"))  h.setAddress(values.get("address"));
        if (values.containsKey("location")) h.setLocation(values.get("location"));
        if (values.containsKey("lat"))      h.setLat(decimal(values.get("lat")));
        if (values.containsKey("lng"))      h.setLng(decimal(values.get("lng")));
        if (values.containsKey("start_date")) h.setStartDate(date(values.get("start_date")));
        if (values.containsKey("scaffold_status")) {
            String s = values.get("scaffold_status");
            // NOT NULL DEFAULT 'NONE' — an empty cell resets rather than nulls, like material.price.
            h.setScaffoldStatus(s == null ? ScaffoldStatus.NONE : ScaffoldStatus.valueOf(s));
        }
        if (values.containsKey("scaffold_start_date")) h.setScaffoldStartDate(date(values.get("scaffold_start_date")));
        if (values.containsKey("scaffold_end_date"))   h.setScaffoldEndDate(date(values.get("scaffold_end_date")));
        houses.save(h);
        if (values.containsKey("name")) houseService.syncHouseDocFolderName(h);
    }

    /** house.lat/lng are numeric(9,6) — round here so the stored value matches the snapshot. */
    private static BigDecimal round6(BigDecimal d) {
        return d == null ? null : d.setScale(6, RoundingMode.HALF_UP);
    }

    private static BigDecimal decimal(String s) {
        return s == null ? null : new BigDecimal(s);
    }

    private static LocalDate date(String s) {
        return s == null ? null : LocalDate.parse(s);
    }

    private static ScaffoldStatus scaffoldStatus(String s) {
        return s == null ? null : ScaffoldStatus.valueOf(s);
    }
}
