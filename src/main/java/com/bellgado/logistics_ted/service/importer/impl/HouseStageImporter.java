package com.bellgado.logistics_ted.service.importer.impl;

import com.bellgado.logistics_ted.domain.House;
import com.bellgado.logistics_ted.domain.HouseStage;
import com.bellgado.logistics_ted.repository.HouseRepository;
import com.bellgado.logistics_ted.repository.HouseStageRepository;
import com.bellgado.logistics_ted.service.importer.ColumnType;
import com.bellgado.logistics_ted.service.importer.EntityImporter;
import com.bellgado.logistics_ted.service.importer.ImportErrorCode;
import com.bellgado.logistics_ted.service.importer.ValueNormalizer;
import com.bellgado.logistics_ted.web.importer.csv.CsvRow;
import com.bellgado.logistics_ted.web.importer.csv.CsvValueException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Stage progress from the client's ACTIVE_MASTER sheet — the same file the {@code houses} importer
 * reads, uploaded a second time here. One CSV row is one house (keyed by its TH id, i.e. the house's
 * {@code external_id}); each stage column is one {@code house_stage} row of that house.
 *
 * <p><b>Columns are stages, matched by name, not number.</b> The sheet numbers its stages
 * ({@code 7_Ниво замазка}) from 1 = Конструкция, while our {@code stage_order} starts at 1 =
 * Фундамент and the display order is admin-editable, so the number is stripped and the name looks
 * up {@code stage_type} (Bulgarian or English name, plus spelling aliases). An unmatched column is
 * an ordinary UNKNOWN_COLUMN warning.
 *
 * <p><b>Each stage is three merge fields</b> — {@code stage_<order>_status}, {@code _worker},
 * {@code _note} — keyed by {@code stage_order} so that renaming a stage does not orphan the sync
 * baselines. A cell only emits the fields it actually determines ({@link StageCell}): {@code Не}
 * emits none (the stage is left alone), a note-only cell leaves status and worker alone. The
 * three-way merge then protects app-side progress: a crew leader finishing a stage in the app is
 * kept ({@code KEPT_APP}) until the sheet itself changes that cell.
 *
 * <p><b>Фундамент is inferred while the sheet lacks it.</b> With no foundation column in the file,
 * any later stage in progress or done marks stage 1 DONE (the dashboard locks all other stages until
 * it is); otherwise stage 1 is left alone. A foundation column, once the client adds one, is read
 * like any other stage.
 *
 * <p><b>Never creates a house.</b> The house must already exist with this external id (import
 * {@code houses} first); a row whose key matches none fails with {@code UNRESOLVED_REF}, in validate
 * mode too. Rows bind to the house through {@link #findByExternalKey} (adoption).
 *
 * <p><b>Dates are not invented.</b> The sheet carries none, so DONE/IN_PROGRESS from the sheet set
 * no start/end date (stamping "today" on hundreds of long-finished stages would corrupt the
 * per-stage duration stats); NOT_STARTED clears them, as the app does. The house's
 * {@code current_phase} is recomputed after every update. {@code crew_id} is never touched — the
 * sheet names people by nickname, which is stored as text in {@code worker_name} only.
 *
 * <p>{@code @Component}, not {@code @Service}: keeps it out of {@code ServiceLoggingAspect}.
 */
@Component
public class HouseStageImporter implements EntityImporter {

    public static final String ENTITY_TYPE = "house_stages";

    private static final int WORKER_MAX = 120;

    /** Фундамент. stage_order 1 is the foundation by convention — the FE's stage lock relies on it too. */
    static final int FOUNDATION_ORDER = 1;

    private static final Pattern NUMBER_PREFIX = Pattern.compile("^\\d+\\s*[_.)-]\\s*");
    private static final Pattern FIELD_KEY = Pattern.compile("^stage_(\\d+)_(status|worker|note)$");

    /** The sheet's spellings that differ from ours (lower-case, after the number is stripped). */
    private static final Map<String, String> SPELLING_ALIASES = Map.of("первази", "първази");

    private final HouseRepository houses;
    private final HouseStageRepository stages;

    public HouseStageImporter(HouseRepository houses, HouseStageRepository stages) {
        this.houses = houses;
        this.stages = stages;
    }

    @Override public String name()       { return "house-stages"; }
    @Override public String entityType() { return ENTITY_TYPE; }
    @Override public List<String> dependsOn() { return List.of("houses"); }
    @Override public Set<String> requiredColumns() { return Set.of(); }

    /** One text column per stage type, named by its Bulgarian name, in display order. */
    @Override
    public Map<String, ColumnType> columns() {
        Map<String, ColumnType> c = new LinkedHashMap<>();
        for (StageType t : stageTypes()) c.put(t.column(), ColumnType.TEXT);
        return c;
    }

    @Override
    public UnaryOperator<String> headerResolver() {
        Map<String, String> byName = new LinkedHashMap<>();
        for (StageType t : stageTypes()) {
            byName.put(t.column(), t.column());
            if (t.nameEn() != null && !t.nameEn().isBlank()) byName.putIfAbsent(norm(t.nameEn()), t.column());
        }
        return h -> {
            if (h.equals("project_id")) return "key";
            String bare = norm(NUMBER_PREFIX.matcher(h).replaceFirst(""));
            bare = SPELLING_ALIASES.getOrDefault(bare, bare);
            return byName.getOrDefault(bare, h);
        };
    }

    @Override
    public Map<String, String> readRow(CsvRow row) {
        String key = row.requiredText(keyColumn());
        if (houses.findByExternalId(key).isEmpty()) {
            throw new CsvValueException(row.line(), keyColumn(), key, ImportErrorCode.UNRESOLVED_REF,
                "No house has the external id '" + key + "'. Import it with the 'houses' importer first.");
        }
        Map<String, String> v = new LinkedHashMap<>();
        boolean foundationInFile = false;
        boolean laterStageStarted = false;
        for (StageType t : stageTypes()) {
            if (!row.has(t.column())) continue;
            StageCell cell = StageCell.parse(row.raw(t.column()));
            if (t.order() == FOUNDATION_ORDER) foundationInFile = true;
            else if ("IN_PROGRESS".equals(cell.status()) || "DONE".equals(cell.status())) laterStageStarted = true;
            if (cell.worker() != null && cell.worker().length() > WORKER_MAX) {
                throw new CsvValueException(row.line(), t.column(), cell.worker(), ImportErrorCode.TOO_LONG,
                    "Worker name is longer than " + WORKER_MAX + " characters.");
            }
            if (cell.status() != null) v.put(field(t.order(), "status"), text(cell.status()));
            if (cell.worker() != null) v.put(field(t.order(), "worker"), text(cell.worker()));
            if (cell.note() != null)   v.put(field(t.order(), "note"),   text(cell.note()));
        }
        // The client's sheet has no Фундамент column (they had no crew for it until now). Work on any
        // later stage means the foundation is in, and the dashboard locks every other stage until
        // stage 1 is DONE — so infer it. Only ever upgrades: with nothing started, stage 1 is left to
        // the app; once the sheet carries the column, its cell is used like any other.
        if (!foundationInFile && laterStageStarted) {
            v.put(field(FOUNDATION_ORDER, "status"), "DONE");
        }
        return v;
    }

    @Override
    public Map<String, String> project(Long entityId) {
        if (!houses.existsById(entityId.intValue())) return null;
        Map<String, String> v = new LinkedHashMap<>();
        for (HouseStage s : stages.findByHouseIdInDisplayOrder(entityId.intValue())) {
            v.put(field(s.getStageOrder(), "status"), text(s.getStatus()));
            v.put(field(s.getStageOrder(), "worker"), text(s.getWorkerName()));
            v.put(field(s.getStageOrder(), "note"),   text(s.getNotes()));
        }
        return v;
    }

    /** Unreachable in practice: {@link #readRow} rejects keys with no house, which are then adopted. */
    @Override
    public Long create(Map<String, String> values) {
        throw new IllegalStateException("The house-stages import never creates a house; import houses first.");
    }

    @Override
    public Long findByExternalKey(String externalKey) {
        return houses.findByExternalId(externalKey).map(h -> Long.valueOf(h.getId())).orElse(null);
    }

    @Override
    public void update(Long entityId, Map<String, String> values) {
        House house = houses.findById(entityId.intValue()).orElseThrow(
            () -> new IllegalStateException("House " + entityId + " disappeared mid-import"));

        Map<Integer, Map<String, String>> byStage = new TreeMap<>();
        values.forEach((k, val) -> {
            Matcher m = FIELD_KEY.matcher(k);
            if (m.matches()) byStage.computeIfAbsent(Integer.valueOf(m.group(1)), o -> new LinkedHashMap<>())
                                    .put(m.group(2), val);
        });
        Map<Integer, StageType> types = new LinkedHashMap<>();
        for (StageType t : stageTypes()) types.put(t.order(), t);

        byStage.forEach((order, fields) -> {
            HouseStage s = stages.findByHouseIdAndStageOrder(house.getId(), order).orElseGet(() -> {
                StageType t = types.get(order);
                HouseStage n = new HouseStage();
                n.setHouse(house);
                n.setStageOrder(order);
                n.setStageName(t != null ? t.name() : "Stage " + order);
                n.setStageNameEn(t != null ? t.nameEn() : null);
                return n;
            });
            if (fields.containsKey("status")) {
                String status = fields.get("status") == null ? "NOT_STARTED" : fields.get("status");
                s.setStatus(status);
                if ("NOT_STARTED".equals(status)) { s.setStartDate(null); s.setEndDate(null); }
            }
            if (fields.containsKey("worker")) s.setWorkerName(fields.get("worker"));
            if (fields.containsKey("note"))   s.setNotes(fields.get("note"));
            s.setUpdatedAt(LocalDateTime.now());
            stages.save(s);
        });

        stages.flush();
        List<String> inProgress = stages.findAllInProgressStageNames(house.getId());
        house.setCurrentPhase(inProgress.isEmpty() ? null : String.join(", ", inProgress));
        houses.save(house);
    }

    private List<StageType> stageTypes() {
        return stages.findDistinctStageTypes().stream()
            .map(r -> new StageType((Integer) r[0], (String) r[1], (String) r[2]))
            .toList();
    }

    private static String field(int order, String part) {
        return "stage_" + order + "_" + part;
    }

    private static String text(String s) {
        return ValueNormalizer.normalize(s, ColumnType.TEXT);
    }

    /** Header form: lower-case, single spaces. */
    private static String norm(String s) {
        return s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private record StageType(int order, String name, String nameEn) {
        String column() { return norm(name); }
    }
}
