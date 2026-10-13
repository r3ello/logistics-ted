package com.bellgado.logistics_ted.service.importer.impl;

import com.bellgado.logistics_ted.domain.Crew;
import com.bellgado.logistics_ted.domain.House;
import com.bellgado.logistics_ted.domain.HouseStage;
import com.bellgado.logistics_ted.domain.HouseStageCrewLog;
import com.bellgado.logistics_ted.repository.CrewRepository;
import com.bellgado.logistics_ted.repository.HouseRepository;
import com.bellgado.logistics_ted.repository.HouseStageCrewLogRepository;
import com.bellgado.logistics_ted.repository.HouseStageRepository;
import com.bellgado.logistics_ted.service.CrewMembershipService;
import com.bellgado.logistics_ted.service.importer.ColumnType;
import com.bellgado.logistics_ted.service.importer.EntityImporter;
import com.bellgado.logistics_ted.service.importer.ImportErrorCode;
import com.bellgado.logistics_ted.service.importer.ValueNormalizer;
import com.bellgado.logistics_ted.web.importer.csv.CsvRow;
import com.bellgado.logistics_ted.web.importer.csv.CsvValueException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
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
 * <p><b>Each stage is up to four merge fields</b> — {@code stage_<order>_status}, {@code _crew},
 * {@code _worker}, {@code _note} — keyed by {@code stage_order} so that renaming a stage does not
 * orphan the sync baselines. A cell only emits the fields it actually determines ({@link StageCell}): {@code Не}
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
 * {@code current_phase} is recomputed after every update.
 *
 * <p><b>The name in a cell is a crew.</b> The sheet names a crew by its leader's nickname, the same
 * one the staff sheet ({@code workers} importer) gives it in {@code Бригада Име}. A crew is looked up
 * among the crews of the stage's group ({@code stage_type.main_stage_bg}): {@code Любо} on a roof
 * stage and {@code Любо} on insulation are two crews. Nickname first, then the leader's full name
 * ({@code Иво Димитров}). One match sets {@code crew_id}, and {@code worker_name} becomes the
 * leader's name, as when a crew is picked in the matrix; the {@code _worker} field is then not
 * emitted, since the crew owns that text. No match, or two, keeps the name as text, leaves the stage
 * without a crew and warns {@code CREW_NOT_FOUND}. Stages with no crew group (Ниво замазка,
 * Мълниезащита, ...) keep the name as text only, as before.
 *
 * <p>The crew field's value is the crew's sheet identity, {@code Конструкция · Влади} (or
 * {@code #id} for a crew the staff sheet never named), so the report reads like the sheet and a
 * leader change does not look like an edit. Crew side effects follow the matrix: the crew's current
 * house moves with {@code IN_PROGRESS}, and its history ({@code house_stage_crew_log}) gets an entry
 * when it starts a stage, closed when the stage is done. Stages already done when first linked get
 * no history — the sheet has no dates, and "finished today" would falsify the durations.
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
    private static final Pattern FIELD_KEY = Pattern.compile("^stage_(\\d+)_(status|crew|worker|note)$");

    /** The sheet's spellings that differ from ours (lower-case, after the number is stripped). */
    private static final Map<String, String> SPELLING_ALIASES = Map.of("первази", "първази");

    /** Separates a crew's group from its nickname in the crew field ({@code Конструкция · Влади}). */
    static final String CREW_SEPARATOR = " · ";

    private final HouseRepository houses;
    private final HouseStageRepository stages;
    private final CrewRepository crews;
    private final HouseStageCrewLogRepository stageLogs;
    private final CrewMembershipService membership;
    private final ZoneId zone;

    public HouseStageImporter(HouseRepository houses, HouseStageRepository stages, CrewRepository crews,
                              HouseStageCrewLogRepository stageLogs, CrewMembershipService membership,
                              @Value("${app.timezone:Europe/Sofia}") String timezone) {
        this.houses = houses;
        this.stages = stages;
        this.crews = crews;
        this.stageLogs = stageLogs;
        this.membership = membership;
        this.zone = ZoneId.of(timezone);
    }

    @Override public String name()       { return "house-stages"; }
    @Override public String entityType() { return ENTITY_TYPE; }
    @Override public List<String> dependsOn() { return List.of("houses", "workers"); }
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

    /** {@code stage_8_status} → {@code Stage 'Ниво замазка' (status)}: the sheet's column, not ours. */
    @Override
    public String describeColumn(String column) {
        Matcher m = FIELD_KEY.matcher(column);
        if (!m.matches()) return column;
        int order = Integer.parseInt(m.group(1));
        String name = stageTypes().stream().filter(t -> t.order() == order)
            .map(StageType::name).findFirst().orElse("#" + order);
        return "Stage '" + name + "' (" + m.group(2) + ")";
    }

    @Override
    public Map<String, String> readRow(CsvRow row) {
        return readRow(row, (code, column, message) -> { });
    }

    @Override
    public Map<String, String> readRow(CsvRow row, RowWarnings warnings) {
        String key = row.requiredText(keyColumn());
        if (houses.findByExternalId(key).isEmpty()) {
            throw new CsvValueException(row.line(), keyColumn(), key, ImportErrorCode.UNRESOLVED_REF,
                "No house has the external id '" + key + "'. Import it with the 'houses' importer first.");
        }
        Map<String, String> v = new LinkedHashMap<>();
        Map<Integer, String> groups = stageGroups();
        List<Crew> allCrews = crews.findAll();
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
            if (cell.worker() != null) putWho(v, t, cell.worker(), groups.get(t.order()), allCrews, warnings);
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

    /**
     * Who a cell names, as fields. Cleared → no crew and no worker. On a crew stage, a name that
     * resolves to one crew → the crew field only; one that does not → the text, no crew, a warning.
     * On a stage without crews → the text only.
     */
    private void putWho(Map<String, String> v, StageType t, String who, String group,
                        List<Crew> allCrews, RowWarnings warnings) {
        if (who.isEmpty()) {
            v.put(field(t.order(), "worker"), text(who));
            v.put(field(t.order(), "crew"), text(who));
            return;
        }
        if (group == null) {
            v.put(field(t.order(), "worker"), text(who));
            return;
        }
        List<Crew> hits = crewsNamed(who, t.order(), group, allCrews);
        if (hits.size() == 1) {
            v.put(field(t.order(), "crew"), crewKey(hits.get(0)));
            return;
        }
        v.put(field(t.order(), "worker"), text(who));
        v.put(field(t.order(), "crew"), null);
        warnings.warn(ImportErrorCode.CREW_NOT_FOUND, t.column(), hits.isEmpty()
            ? "'" + who + "' is not a crew of the '" + group + "' group in the staff sheet; kept as text, no crew assigned."
            : "'" + who + "' matches " + hits.size() + " crews of the '" + group + "' group ("
                + String.join(", ", hits.stream().map(HouseStageImporter::crewKey).toList())
                + "); kept as text, no crew assigned.");
    }

    /** The crews of this stage's group answering to the name: by nickname, else by the leader's full name. */
    private static List<Crew> crewsNamed(String who, int order, String group, List<Crew> allCrews) {
        String k = WorkerImporter.matchKey(who);
        List<Crew> able = allCrews.stream().filter(c -> canWork(c, order, group)).toList();
        List<Crew> byNickname = able.stream()
            .filter(c -> c.getNickname() != null && WorkerImporter.matchKey(c.getNickname()).equals(k))
            .toList();
        if (!byNickname.isEmpty()) return byNickname;
        return able.stream()
            .filter(c -> (c.getLeader() != null && WorkerImporter.matchKey(c.getLeader().getName()).equals(k))
                      || WorkerImporter.matchKey(c.getName()).equals(k))
            .toList();
    }

    /**
     * A crew the staff sheet placed works its group's stages ({@code Изолация/Картон} → both); one it
     * never named works the stages it was given in the app.
     */
    private static boolean canWork(Crew c, int order, String group) {
        if (c.getStageGroup() != null) {
            for (String part : c.getStageGroup().split("/")) {
                if (part.trim().equalsIgnoreCase(group)) return true;
            }
            return false;
        }
        return c.getStageOrders() != null && c.getStageOrders().contains(order);
    }

    /** {@code Конструкция · Влади}, or {@code #113} for a crew with no sheet identity. */
    static String crewKey(Crew c) {
        return c.getStageGroup() != null && c.getNickname() != null
            ? c.getStageGroup() + CREW_SEPARATOR + c.getNickname()
            : "#" + c.getId();
    }

    private Crew crewByKey(String key) {
        if (key == null) return null;
        if (key.startsWith("#")) return crews.findById(Integer.valueOf(key.substring(1))).orElse(null);
        int at = key.lastIndexOf(CREW_SEPARATOR);
        if (at < 0) return null;
        return crews.findByStageGroupAndNickname(key.substring(0, at), key.substring(at + CREW_SEPARATOR.length()))
            .orElse(null);
    }

    @Override
    public Map<String, String> project(Long entityId) {
        if (!houses.existsById(entityId.intValue())) return null;
        Map<Integer, Crew> crewById = new HashMap<>();
        for (Crew c : crews.findAll()) crewById.put(c.getId(), c);
        Map<String, String> v = new LinkedHashMap<>();
        for (HouseStage s : stages.findByHouseIdInDisplayOrder(entityId.intValue())) {
            Crew crew = s.getCrewId() == null ? null : crewById.get(s.getCrewId());
            v.put(field(s.getStageOrder(), "status"), text(s.getStatus()));
            v.put(field(s.getStageOrder(), "crew"),   crew == null ? null : crewKey(crew));
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
            String oldStatus = s.getStatus();
            Integer oldCrew = s.getCrewId();
            if (fields.containsKey("status")) {
                String status = fields.get("status") == null ? "NOT_STARTED" : fields.get("status");
                s.setStatus(status);
                if ("NOT_STARTED".equals(status)) { s.setStartDate(null); s.setEndDate(null); }
            }
            if (fields.containsKey("crew")) {
                Crew crew = crewByKey(fields.get("crew"));
                s.setCrewId(crew == null ? null : crew.getId());
                // As when a crew is picked in the matrix: the stage shows its leader.
                if (crew != null) s.setWorkerName(crew.getLeader() != null ? crew.getLeader().getName() : crew.getName());
            }
            if (fields.containsKey("worker")) s.setWorkerName(fields.get("worker"));
            if (fields.containsKey("note"))   s.setNotes(fields.get("note"));
            s.setUpdatedAt(LocalDateTime.now());
            HouseStage saved = stages.save(s);
            crewFollows(house, saved, oldStatus, oldCrew);
        });

        stages.flush();
        List<String> inProgress = stages.findAllInProgressStageNames(house.getId());
        house.setCurrentPhase(inProgress.isEmpty() ? null : String.join(", ", inProgress));
        houses.save(house);
    }

    /**
     * The crew side effects of a stage change, as the matrix applies them: the crew's current house,
     * and its history — opened when it starts a stage (dated today: that is when we learnt it),
     * closed when the stage is done. A stage linked when already done gets no history.
     */
    private void crewFollows(House house, HouseStage s, String oldStatus, Integer oldCrew) {
        Integer crew = s.getCrewId();
        String status = s.getStatus();
        boolean crewChanged = !Objects.equals(oldCrew, crew);
        if (!crewChanged && Objects.equals(oldStatus, status)) return;

        if (crew != null && "IN_PROGRESS".equals(status)) membership.crewStartedAt(crew, house);
        if (oldCrew != null && (crewChanged || "IN_PROGRESS".equals(oldStatus))) {
            membership.recomputeCrewHouse(oldCrew, null);
        }

        if (crew == null) return;
        if ("IN_PROGRESS".equals(status)) {
            var latest = stageLogs.findLatest(house.getId(), s.getStageOrder(), crew);
            if (latest.isPresent() && "IN_PROGRESS".equals(latest.get().getStatus())) return;
            HouseStageCrewLog log = new HouseStageCrewLog();
            log.setHouseId(house.getId());
            log.setHouseName(house.getName());
            log.setStageOrder(s.getStageOrder());
            log.setStageName(s.getStageName());
            log.setStageNameEn(s.getStageNameEn());
            log.setCrewId(crew);
            log.setCrewName(crews.findById(crew).map(Crew::getName).orElse(null));
            log.setStatus("IN_PROGRESS");
            log.setStartDate(today());
            log.setLoggedAt(OffsetDateTime.now());
            stageLogs.save(log);
        } else if ("DONE".equals(status) && !"DONE".equals(oldStatus)) {
            // Only a stage finishing now can close an entry; one linked while already done has none.
            stageLogs.findLatest(house.getId(), s.getStageOrder(), crew)
                .filter(l -> "IN_PROGRESS".equals(l.getStatus())).ifPresent(l -> {
                l.setStatus("DONE");
                l.setEndDate(today());
                l.setLoggedAt(OffsetDateTime.now());
                stageLogs.save(l);
            });
        }
    }

    private LocalDate today() {
        return LocalDate.now(zone);
    }

    /** stage_order → its crew group ({@code Покрив}), for the stages crews work on. */
    private Map<Integer, String> stageGroups() {
        Map<Integer, String> out = new HashMap<>();
        for (Object[] r : stages.findCrewStageGroups()) {
            out.put(((Number) r[0]).intValue(), ((String) r[1]).trim());
        }
        return out;
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
