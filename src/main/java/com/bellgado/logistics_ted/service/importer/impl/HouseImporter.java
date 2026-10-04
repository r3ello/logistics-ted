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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
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
 * and the sync must not fight it (§3.2).
 *
 * <p>Since V20 the source is the client's ACTIVE_MASTER sheet (keyed by the TH id), whose
 * project-level columns all have a target: {@code client_name}, {@code drive_folder_url},
 * {@code google_chat_id}, {@code google_album_id}, {@code google_album_url},
 * {@code calculator_sheet_id}, {@code master_sheet_id}. That sheet has no address, so {@code address}
 * is optional. The older CRM export's other personal data (ЕГН, phone, email, prices) still has no
 * mapping and must never gain one — the orchestrator drops it with {@code UNKNOWN_COLUMN}.
 *
 * <p>{@code @Component} rather than {@code @Service} keeps it out of {@code ServiceLoggingAspect},
 * which logs arguments at DEBUG.
 */
@Component
public class HouseImporter implements EntityImporter {

    /** {@code import_ref.entity_type} for houses — also used by {@code HouseService} to re-key it. */
    public static final String ENTITY_TYPE = "house";

    /** Mirrors {@code house.name varchar(150)} / {@code address varchar(255)} / {@code location varchar(512)}. */
    private static final int NAME_MAX = 150;
    private static final int ADDRESS_MAX = 255;
    private static final int LOCATION_MAX = 512;

    /** The V20 free-text columns and their varchar lengths. */
    private static final Map<String, Integer> TEXT_COLUMNS = linked(
        "client_name",         255,
        "drive_folder_url",    512,
        "google_chat_id",      120,
        "google_album_id",     255,
        "google_album_url",    512,
        "calculator_sheet_id", 120,
        "master_sheet_id",     120);

    private static final Set<String> SCAFFOLD_STATUSES =
        Set.of(ScaffoldStatus.NONE.name(), ScaffoldStatus.AVAILABLE.name(), ScaffoldStatus.IN_USE.name());

    private static final Map<String, ColumnType> COLUMNS = buildColumns();

    private static Map<String, ColumnType> buildColumns() {
        Map<String, ColumnType> c = new LinkedHashMap<>();
        c.put("name",                ColumnType.TEXT);
        c.put("address",             ColumnType.TEXT);
        c.put("location",            ColumnType.TEXT);
        c.put("lat",                 ColumnType.DECIMAL);
        c.put("lng",                 ColumnType.DECIMAL);
        c.put("start_date",          ColumnType.DATE);
        c.put("scaffold_status",     ColumnType.ENUM);
        c.put("scaffold_start_date", ColumnType.DATE);
        c.put("scaffold_end_date",   ColumnType.DATE);
        TEXT_COLUMNS.keySet().forEach(k -> c.put(k, ColumnType.TEXT));
        return Collections.unmodifiableMap(c);
    }

    private final HouseService houseService;
    private final HouseRepository houses;

    public HouseImporter(HouseService houseService, HouseRepository houses) {
        this.houseService = houseService;
        this.houses = houses;
    }

    @Override public String name()       { return "houses"; }
    @Override public String entityType() { return ENTITY_TYPE; }

    @Override public Map<String, ColumnType> columns() { return COLUMNS; }

    @Override public Set<String> requiredColumns() { return Set.of("name"); }

    /**
     * The ACTIVE_MASTER sheet's own headers, so the client's export can be uploaded as-is. Its
     * other columns either already match ({@code location}, {@code client_name}, {@code google_*})
     * or belong to {@code house-stages} and come back as UNKNOWN_COLUMN warnings.
     */
    private static final Map<String, String> MASTER_SHEET_HEADERS = Map.of(
        "project_id",       "key",
        "project_name",     "name",
        "project_link",     "drive_folder_url",
        "calculator_ss_id", "calculator_sheet_id",
        "prj_master_ss_id", "master_sheet_id");

    @Override
    public UnaryOperator<String> headerResolver() {
        return h -> MASTER_SHEET_HEADERS.getOrDefault(h, h);
    }

    @Override
    public Map<String, String> readRow(CsvRow row) {
        Map<String, String> v = new LinkedHashMap<>();
        if (row.has("name")) {
            row.requiredText("name");
            v.put("name", ValueNormalizer.normalize(row.text("name", NAME_MAX), ColumnType.TEXT));
        }
        if (row.has("address")) {
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
        TEXT_COLUMNS.forEach((col, max) -> {
            if (row.has(col)) v.put(col, ValueNormalizer.normalize(row.text(col, max), ColumnType.TEXT));
        });
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
            v.put("client_name",         ValueNormalizer.normalize(h.getClientName(),        ColumnType.TEXT));
            v.put("drive_folder_url",    ValueNormalizer.normalize(h.getDriveFolderUrl(),    ColumnType.TEXT));
            v.put("google_chat_id",      ValueNormalizer.normalize(h.getGoogleChatId(),      ColumnType.TEXT));
            v.put("google_album_id",     ValueNormalizer.normalize(h.getGoogleAlbumId(),     ColumnType.TEXT));
            v.put("google_album_url",    ValueNormalizer.normalize(h.getGoogleAlbumUrl(),    ColumnType.TEXT));
            v.put("calculator_sheet_id", ValueNormalizer.normalize(h.getCalculatorSheetId(), ColumnType.TEXT));
            v.put("master_sheet_id",     ValueNormalizer.normalize(h.getMasterSheetId(),     ColumnType.TEXT));
            return v;
        }).orElse(null);
    }

    @Override
    public Long create(Map<String, String> values) {
        return create(null, values);
    }

    /** The key is stored as the house's {@code external_id}, so the client sees their id in the app. */
    @Override
    public Long create(String externalKey, Map<String, String> values) {
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
            values.get("scaffold_end_date"),
            null,                                       // google_doc_url — not imported
            externalKey,
            values.get("client_name"),
            values.get("drive_folder_url"),
            values.get("google_chat_id"),
            values.get("google_album_id"),
            values.get("google_album_url"),
            values.get("calculator_sheet_id"),
            values.get("master_sheet_id"));
        return Long.valueOf(houseService.create(req).id());
    }

    /** A house created by hand with this CRM id typed in, which the sync then adopts. */
    @Override
    public Long findByExternalKey(String externalKey) {
        return houses.findByExternalId(externalKey).map(h -> Long.valueOf(h.getId())).orElse(null);
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
        if (values.containsKey("client_name"))         h.setClientName(values.get("client_name"));
        if (values.containsKey("drive_folder_url"))    h.setDriveFolderUrl(values.get("drive_folder_url"));
        if (values.containsKey("google_chat_id"))      h.setGoogleChatId(values.get("google_chat_id"));
        if (values.containsKey("google_album_id"))     h.setGoogleAlbumId(values.get("google_album_id"));
        if (values.containsKey("google_album_url"))    h.setGoogleAlbumUrl(values.get("google_album_url"));
        if (values.containsKey("calculator_sheet_id")) h.setCalculatorSheetId(values.get("calculator_sheet_id"));
        if (values.containsKey("master_sheet_id"))     h.setMasterSheetId(values.get("master_sheet_id"));
        houses.save(h);
        if (values.containsKey("name")) houseService.syncHouseDocFolderName(h);
    }

    private static Map<String, Integer> linked(Object... kv) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], (Integer) kv[i + 1]);
        return Collections.unmodifiableMap(m);
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
