package com.bellgado.logistics_ted.service.importer.impl;

import com.bellgado.logistics_ted.config.WorkerCredentialSeeder;
import com.bellgado.logistics_ted.domain.Crew;
import com.bellgado.logistics_ted.domain.EmploymentType;
import com.bellgado.logistics_ted.domain.Worker;
import com.bellgado.logistics_ted.domain.WorkerRole;
import com.bellgado.logistics_ted.repository.CrewRepository;
import com.bellgado.logistics_ted.repository.WorkerRepository;
import com.bellgado.logistics_ted.service.CrewMembershipService;
import com.bellgado.logistics_ted.service.importer.ColumnType;
import com.bellgado.logistics_ted.service.importer.EntityImporter;
import com.bellgado.logistics_ted.service.importer.ImportErrorCode;
import com.bellgado.logistics_ted.service.importer.ValueNormalizer;
import com.bellgado.logistics_ted.web.importer.csv.CsvRow;
import com.bellgado.logistics_ted.web.importer.csv.CsvValueException;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Workers and their crews, from the client's staff sheet (Персонал_Мастър). One row is one person,
 * keyed by the sheet's {@code ID} ({@code FR001}), which is also stored as {@code worker.external_id}.
 *
 * <p><b>Crews are derived, not imported.</b> The sheet names a crew by (group, leader nickname) —
 * {@code Конструкция} + {@code Влади} — and that pair is the crew's key ({@code crew.stage_group} +
 * {@code crew.nickname}, Flyway V21). A row is placed in its crew, the crew created on first sight
 * with the group's stages; a {@code Главен} row makes that person the crew's leader. Membership
 * rules go through {@link CrewMembershipService}, the same code the worker and crew screens use.
 *
 * <p><b>First run over seeded data.</b> The V1 seed came from an older copy of this sheet, without
 * ids. {@link #adopt} therefore recognises an unkeyed worker by name (NFC, homoglyphs fixed, case
 * folded) and, on apply, stores the key on them — reported as {@code ADOPTED_EXISTING}, so a validate
 * run shows exactly who will be updated rather than duplicated. Two candidates with one name are
 * narrowed by crew; still two is {@code AMBIGUOUS_MATCH}, never a guess. The seeded crews are adopted
 * the same way: the first person placed from an un-nicknamed crew whose stages fit the group stamps
 * the sheet's group + nickname on it.
 *
 * <p><b>The sheet is dirty, and contact cells are cleaned rather than rejected</b>: notes typed into
 * email/phone/photo cells ({@code няма}, {@code чакам}, {@code Иска да говори с Теодор}) are read as
 * empty with a {@code CELL_IGNORED} warning; Latin look-alike letters inside Cyrillic words
 * ({@code Eвгени}) and decomposed {@code й} are normalised so one crew does not become two. A row
 * with no real name ({@code 1}, {@code 2}, blank) fails.
 *
 * <p><b>Never touched:</b> {@code username}, PIN, {@code crew.house_id}, {@code manager_id}. New
 * people get credentials like an admin-created worker does. Nobody is deleted: people missing from
 * the file come back as {@code NOT_IN_FILE} warnings. The sheet's social-insurance amounts
 * ({@code Осигурен}, {@code от Фирма}, {@code от Служител}) have no column here on purpose and are
 * dropped as {@code UNKNOWN_COLUMN}.
 *
 * <p>{@code @Component}, not {@code @Service}: rows carry personal data (ID card numbers) that
 * {@code ServiceLoggingAspect} would log at DEBUG.
 */
@Component
public class WorkerImporter implements EntityImporter {

    public static final String ENTITY_TYPE = "worker";

    private static final Map<String, ColumnType> COLUMNS = buildColumns();

    private static Map<String, ColumnType> buildColumns() {
        Map<String, ColumnType> c = new LinkedHashMap<>();
        c.put("name",            ColumnType.TEXT);
        c.put("stage_group",     ColumnType.TEXT);
        c.put("crew",            ColumnType.TEXT);
        c.put("role",            ColumnType.ENUM);
        c.put("employment_type", ColumnType.ENUM);
        c.put("phone",           ColumnType.TEXT);
        c.put("email",           ColumnType.TEXT);
        c.put("photo_url",       ColumnType.TEXT);
        c.put("location",        ColumnType.TEXT);
        c.put("hire_date",       ColumnType.DATE);
        c.put("id_card_number",  ColumnType.TEXT);
        c.put("company",         ColumnType.TEXT);
        c.put("clothing_size",   ColumnType.TEXT);
        c.put("clothing_issued", ColumnType.TEXT);
        return Collections.unmodifiableMap(c);
    }

    /** The staff sheet's own headers (lower-cased), so the client's export uploads as-is. */
    private static final Map<String, String> SHEET_HEADERS = Map.ofEntries(
        Map.entry("id",              "key"),
        Map.entry("група",           "stage_group"),
        Map.entry("бригада име",     "crew"),
        Map.entry("име служител",    "name"),
        Map.entry("роля",            "role"),
        Map.entry("вид служител",    "employment_type"),
        Map.entry("телефон",         "phone"),
        Map.entry("имейл",           "email"),
        Map.entry("снимки",          "photo_url"),
        Map.entry("локация",         "location"),
        Map.entry("дата постъпване", "hire_date"),
        Map.entry("лична карта",     "id_card_number"),
        Map.entry("фирма",           "company"),
        Map.entry("размер дрехи",    "clothing_size"),
        Map.entry("облекло",         "clothing_issued"));

    private static final Map<String, String> ROLES = Map.of(
        "главен",      WorkerRole.CREW_LEADER.name(),
        "служител",    WorkerRole.CREW_MEMBER.name(),
        "crew_leader", WorkerRole.CREW_LEADER.name(),
        "crew_member", WorkerRole.CREW_MEMBER.name());

    private static final Map<String, String> EMPLOYMENT_TYPES = Map.of(
        "вътрешен",      EmploymentType.INTERNAL.name(),
        "външен",        EmploymentType.EXTERNAL.name(),
        "подизпълнител", EmploymentType.SUBCONTRACTOR.name(),
        "internal",      EmploymentType.INTERNAL.name(),
        "external",      EmploymentType.EXTERNAL.name(),
        "subcontractor", EmploymentType.SUBCONTRACTOR.name());

    /** Cells that mean "nothing here" and are not worth a warning. */
    private static final Set<String> SILENT_BLANKS = Set.of(".", "..", "-", "—", "–");

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern PHONE = Pattern.compile("^\\+?\\d{6,15}$");
    private static final Pattern URL = Pattern.compile("^https?://\\S+$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CYRILLIC = Pattern.compile("\\p{IsCyrillic}");
    private static final Pattern LETTERS = Pattern.compile("\\p{L}.*\\p{L}");

    /** Latin letters that look Cyrillic, as typed by a Bulgarian keyboard left on EN. */
    private static final Map<Character, Character> HOMOGLYPHS = Map.ofEntries(
        Map.entry('A', 'А'), Map.entry('B', 'В'), Map.entry('C', 'С'), Map.entry('E', 'Е'),
        Map.entry('H', 'Н'), Map.entry('K', 'К'), Map.entry('M', 'М'), Map.entry('O', 'О'),
        Map.entry('P', 'Р'), Map.entry('T', 'Т'), Map.entry('X', 'Х'), Map.entry('Y', 'У'),
        Map.entry('a', 'а'), Map.entry('c', 'с'), Map.entry('e', 'е'), Map.entry('o', 'о'),
        Map.entry('p', 'р'), Map.entry('x', 'х'), Map.entry('y', 'у'));

    private final WorkerRepository workers;
    private final CrewRepository crews;
    private final CrewMembershipService membership;
    private final WorkerCredentialSeeder credentials;
    private final Supplier<Map<String, List<Integer>>> stageGroupLoader;

    /** Group label (lower-case) → its stages, from {@code stage_type.main_stage_bg}; reloaded per file. */
    private volatile Map<String, List<Integer>> stageGroups;

    @Autowired
    public WorkerImporter(WorkerRepository workers, CrewRepository crews, CrewMembershipService membership,
                          WorkerCredentialSeeder credentials, JdbcTemplate jdbc) {
        this(workers, crews, membership, credentials, () -> loadStageGroups(jdbc));
    }

    /** For tests: the stage groups without a database. */
    WorkerImporter(WorkerRepository workers, CrewRepository crews, CrewMembershipService membership,
                   WorkerCredentialSeeder credentials, Supplier<Map<String, List<Integer>>> stageGroupLoader) {
        this.workers = workers;
        this.crews = crews;
        this.membership = membership;
        this.credentials = credentials;
        this.stageGroupLoader = stageGroupLoader;
    }

    private static Map<String, List<Integer>> loadStageGroups(JdbcTemplate jdbc) {
        Map<String, List<Integer>> out = new TreeMap<>();
        jdbc.query("SELECT stage_order, main_stage_bg FROM stage_type "
                 + "WHERE has_crew AND main_stage_bg IS NOT NULL ORDER BY stage_order",
            rs -> {
                out.computeIfAbsent(rs.getString(2).trim().toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                   .add(rs.getInt(1));
            });
        return out;
    }

    @Override public String name()       { return "workers"; }
    @Override public String entityType() { return ENTITY_TYPE; }

    @Override public Map<String, ColumnType> columns() { return COLUMNS; }

    @Override public Set<String> requiredColumns() { return Set.of("name", "stage_group", "crew"); }

    /** Called once per file: also refreshes the stage groups, so a renamed stage group is picked up. */
    @Override
    public UnaryOperator<String> headerResolver() {
        stageGroups = stageGroupLoader.get();
        return h -> SHEET_HEADERS.getOrDefault(h, h);
    }

    @Override
    public Map<String, String> readRow(CsvRow row) {
        return readRow(row, (code, column, message) -> { });
    }

    @Override
    public Map<String, String> readRow(CsvRow row, RowWarnings warnings) {
        Map<String, String> v = new LinkedHashMap<>();
        if (row.has("name")) {
            String name = cyrillic(row.text("name", 150));
            if (name == null || !LETTERS.matcher(name).find()) {
                throw new CsvValueException(row.line(), "name", row.raw("name"), ImportErrorCode.EMPTY_REQUIRED,
                    "A person's name is required; '" + Objects.toString(row.raw("name"), "") + "' is not one.");
            }
            v.put("name", text(name));
        }
        if (row.has("stage_group")) {
            String group = canonicalGroup(cyrillic(row.requiredText("stage_group")));
            if (stagesOf(group).isEmpty()) {
                throw new CsvValueException(row.line(), "stage_group", row.raw("stage_group"),
                    ImportErrorCode.INVALID_ENUM, "Unknown group. Known: " + String.join(", ", knownGroups()) + ".");
            }
            if (group.length() > 100) {
                throw new CsvValueException(row.line(), "stage_group", group, ImportErrorCode.TOO_LONG,
                    "Value is " + group.length() + " characters; the limit is 100.");
            }
            v.put("stage_group", text(group));
        }
        if (row.has("crew")) {
            row.requiredText("crew");
            v.put("crew", text(cyrillic(row.text("crew", 100))));
        }
        if (row.has("role")) {
            v.put("role", mapped(row, "role", ROLES));
        }
        if (row.has("employment_type")) {
            v.put("employment_type", mapped(row, "employment_type", EMPLOYMENT_TYPES));
        }
        if (row.has("phone")) {
            v.put("phone", contact(row, "phone", 50, warnings, s -> {
                String digits = s.replaceAll("[\\s().\\-/]", "");
                return PHONE.matcher(digits).matches() ? digits : null;
            }, "not a phone number"));
        }
        if (row.has("email")) {
            v.put("email", contact(row, "email", 255, warnings,
                s -> EMAIL.matcher(s).matches() ? s : null, "not an email address"));
        }
        if (row.has("photo_url")) {
            v.put("photo_url", contact(row, "photo_url", 512, warnings,
                s -> URL.matcher(s).matches() ? s : null, "not a link"));
        }
        if (row.has("location")) {
            String loc = row.text("location", 255);
            v.put("location", loc == null || SILENT_BLANKS.contains(loc) ? null : text(cyrillic(loc)));
        }
        if (row.has("hire_date")) {
            v.put("hire_date", ValueNormalizer.normalize(row.date("hire_date"), ColumnType.DATE));
        }
        if (row.has("id_card_number"))  v.put("id_card_number",  plain(row, "id_card_number", 40));
        if (row.has("company"))         v.put("company",         plain(row, "company", 150));
        if (row.has("clothing_size"))   v.put("clothing_size",   plain(row, "clothing_size", 20));
        if (row.has("clothing_issued")) v.put("clothing_issued", plain(row, "clothing_issued", 255));
        return v;
    }

    @Override
    public Map<String, String> project(Long entityId) {
        return workers.findById(entityId.intValue()).map(this::projectOf).orElse(null);
    }

    private Map<String, String> projectOf(Worker w) {
        Crew c = w.getCrew();
        Map<String, String> v = new LinkedHashMap<>();
        v.put("name",            text(w.getName()));
        v.put("stage_group",     text(c == null ? null : c.getStageGroup()));
        v.put("crew",            text(c == null ? null : c.getNickname()));
        v.put("role",            ValueNormalizer.normalize(w.getRole(), ColumnType.ENUM));
        v.put("employment_type", ValueNormalizer.normalize(w.getEmploymentType(), ColumnType.ENUM));
        v.put("phone",           text(w.getPhone()));
        v.put("email",           text(w.getEmail()));
        v.put("photo_url",       text(w.getPhotoUrl()));
        v.put("location",        text(w.getLocation()));
        v.put("hire_date",       ValueNormalizer.normalize(w.getHireDate(), ColumnType.DATE));
        v.put("id_card_number",  text(w.getIdCardNumber()));
        v.put("company",         text(w.getCompany()));
        v.put("clothing_size",   text(w.getClothingSize()));
        v.put("clothing_issued", text(w.getClothingIssued()));
        return v;
    }

    @Override
    public Long create(Map<String, String> values) {
        return create(null, values);
    }

    @Override
    public Long create(String externalKey, Map<String, String> values) {
        Worker w = new Worker();
        w.setLocation("");
        apply(w, values);
        w.setExternalId(externalKey);
        credentials.assignCredentials(w);
        w = workers.save(w);
        place(w, values.get("stage_group"), values.get("crew"), w.getRole(), values);
        return Long.valueOf(w.getId());
    }

    @Override
    public Long findByExternalKey(String externalKey) {
        return workers.findByExternalId(externalKey).map(w -> Long.valueOf(w.getId())).orElse(null);
    }

    @Override
    public Adoption adopt(String externalKey, Map<String, String> sheet, boolean apply) {
        Long byKey = findByExternalKey(externalKey);
        if (byKey != null) return new Adoption(byKey, "created in the app with the same external id");

        String name = sheet.get("name");
        if (name == null) return null;
        String wanted = matchKey(name);
        List<Worker> candidates = workers.findByExternalIdIsNull().stream()
            .filter(w -> wanted.equals(matchKey(w.getName())))
            .toList();
        if (candidates.size() > 1) {
            List<Integer> stages = stagesOf(sheet.get("stage_group"));
            candidates = candidates.stream()
                .filter(w -> w.getCrew() != null && sameCrew(w.getCrew(), sheet.get("stage_group"),
                                                             sheet.get("crew"), stages))
                .toList();
            if (candidates.size() != 1) {
                throw new CsvValueException(0, "name", name, ImportErrorCode.AMBIGUOUS_MATCH,
                    "More than one existing worker is called '" + name + "' and the crew does not tell "
                        + "them apart; give one of them their ID in the application first.");
            }
        }
        if (candidates.isEmpty()) return null;
        Worker w = candidates.get(0);
        if (apply) {
            w.setExternalId(externalKey);
            workers.save(w);
        }
        return new Adoption(Long.valueOf(w.getId()), "with the same name, not yet linked to the sheet");
    }

    @Override
    public List<String> notInFile(Set<Long> entityIdsInFile) {
        List<String> out = new ArrayList<>();
        for (Worker w : workers.findAll()) {
            if (w.getRole() == WorkerRole.CREW_MANAGER || entityIdsInFile.contains(Long.valueOf(w.getId()))) continue;
            Crew c = w.getCrew();
            String crew = c == null ? "no crew" : "crew " + (c.getNickname() != null
                ? c.getStageGroup() + " / " + c.getNickname() : c.getName());
            out.add("Worker '" + w.getName() + "' (id " + w.getId() + ", " + crew
                + ") is not in the file; left unchanged.");
        }
        return out;
    }

    @Override
    public void update(Long entityId, Map<String, String> values) {
        Worker w = workers.findById(entityId.intValue()).orElseThrow(
            () -> new IllegalStateException("Worker " + entityId + " disappeared mid-import"));
        String oldName = w.getName();
        apply(w, values);
        w = workers.save(w);

        Crew crew = w.getCrew();
        boolean leadsCrew = crew != null && crew.getLeader() != null && crew.getLeader().getId().equals(w.getId());
        if (values.containsKey("name") && leadsCrew && !Objects.equals(oldName, w.getName())) {
            if (Objects.equals(crew.getName(), oldName)) {
                crew.setName(w.getName());
                crews.save(crew);
            }
            membership.leaderRenamed(crew, w.getName());
        }

        if (values.containsKey("stage_group") || values.containsKey("crew") || values.containsKey("role")
                || values.containsKey("location")) {
            Map<String, String> now = projectOf(w);
            String group = values.containsKey("stage_group") ? values.get("stage_group") : now.get("stage_group");
            String nick  = values.containsKey("crew")        ? values.get("crew")        : now.get("crew");
            if (group != null && nick != null) place(w, group, nick, w.getRole(), values);
        }
    }

    // ── placement ─────────────────────────────────────────────────────────────

    /** Puts the worker in the (group, nickname) crew and makes them its leader if their role says so. */
    private void place(Worker w, String group, String nickname, WorkerRole role, Map<String, String> values) {
        if (group == null || nickname == null) return;
        Crew target = resolveCrew(w, group, nickname);
        Crew old = w.getCrew();

        if (old == null || !old.getId().equals(target.getId())) {
            if (old != null && old.getLeader() != null && old.getLeader().getId().equals(w.getId())) {
                membership.clearLeader(old);
            }
            membership.join(w, target);
        }

        boolean isLeader = target.getLeader() != null && target.getLeader().getId().equals(w.getId());
        if (role == WorkerRole.CREW_LEADER && !isLeader) {
            Worker displaced = membership.replaceLeader(target, w);
            // A crew made by the import is named after its nickname until it has a leader; one
            // made in the app is named after its leader. Follow the leader in both cases.
            if (Objects.equals(target.getName(), nickname)
                    || (displaced != null && Objects.equals(target.getName(), displaced.getName()))) {
                target.setName(w.getName());
                crews.save(target);
            }
        } else if (role != WorkerRole.CREW_LEADER && isLeader) {
            membership.clearLeader(target);
        }

        // The sheet gives a crew's home town on its leader's row. Taken from the leader as they now
        // are, not from the changed columns: on a first run the town is often already right on the
        // worker and only the crew lacks it.
        if (role == WorkerRole.CREW_LEADER) {
            String loc = w.getLocation() == null ? "" : w.getLocation();
            if (!loc.equals(target.getLocation())) {
                target.setLocation(loc);
                crews.save(target);
            }
        }
    }

    /**
     * The crew with this sheet identity; else the worker's own seeded crew if the sheet has never
     * named it and its stages fit the group (adopting it); else a new crew with the group's stages.
     */
    private Crew resolveCrew(Worker w, String group, String nickname) {
        Crew byKey = crews.findByStageGroupAndNickname(group, nickname).orElse(null);
        if (byKey != null) return byKey;

        List<Integer> stages = stagesOf(group);
        Crew current = w.getCrew();
        if (current != null && current.getNickname() == null && overlaps(current.getStageOrders(), stages)) {
            current.setStageGroup(group);
            current.setNickname(nickname);
            return crews.save(current);
        }

        Crew c = new Crew();
        c.setName(nickname);
        c.setStageGroup(group);
        c.setNickname(nickname);
        c.setStageOrders(new ArrayList<>(stages));
        c.setLocation("");
        return crews.save(c);
    }

    private boolean sameCrew(Crew c, String group, String nickname, List<Integer> stages) {
        if (c.getNickname() != null) {
            return Objects.equals(c.getNickname(), nickname) && Objects.equals(c.getStageGroup(), group);
        }
        return overlaps(c.getStageOrders(), stages);
    }

    private static boolean overlaps(List<Integer> a, List<Integer> b) {
        if (a == null || b == null) return false;
        for (Integer x : a) if (b.contains(x)) return true;
        return false;
    }

    // ── stage groups ──────────────────────────────────────────────────────────

    /** {@code Изолация/ Картон} → {@code Изолация/Картон}: parts trimmed, case kept. */
    static String canonicalGroup(String raw) {
        if (raw == null) return null;
        List<String> parts = new ArrayList<>();
        for (String p : raw.split("/")) {
            String t = p.trim().replaceAll("\\s+", " ");
            if (!t.isEmpty()) parts.add(t);
        }
        return String.join("/", parts);
    }

    /** A group's stages; a combined group ({@code Изолация/Картон}) is the union of its parts. */
    private List<Integer> stagesOf(String group) {
        if (group == null) return List.of();
        Map<String, List<Integer>> groups = groups();
        List<Integer> out = new ArrayList<>();
        for (String part : group.split("/")) {
            List<Integer> s = groups.get(part.trim().toLowerCase(Locale.ROOT));
            if (s == null) return List.of();
            for (Integer o : s) if (!out.contains(o)) out.add(o);
        }
        return out;
    }

    private List<String> knownGroups() {
        return new ArrayList<>(groups().keySet());
    }

    private Map<String, List<Integer>> groups() {
        Map<String, List<Integer>> g = stageGroups;
        if (g == null) {
            g = stageGroupLoader.get();
            stageGroups = g;
        }
        return g;
    }

    // ── cell helpers ──────────────────────────────────────────────────────────

    private void apply(Worker w, Map<String, String> values) {
        if (values.containsKey("name"))            w.setName(values.get("name"));
        if (values.containsKey("role")) {
            String r = values.get("role");
            // NOT NULL with a default — an empty cell resets rather than nulls, like house.scaffold_status.
            w.setRole(r == null ? WorkerRole.CREW_MEMBER : WorkerRole.valueOf(r));
        }
        if (values.containsKey("employment_type")) {
            String t = values.get("employment_type");
            w.setEmploymentType(t == null ? null : EmploymentType.valueOf(t));
        }
        if (values.containsKey("phone"))           w.setPhone(values.get("phone"));
        if (values.containsKey("email"))           w.setEmail(values.get("email"));
        if (values.containsKey("photo_url"))       w.setPhotoUrl(values.get("photo_url"));
        if (values.containsKey("location"))        w.setLocation(values.get("location") == null ? "" : values.get("location"));
        if (values.containsKey("hire_date")) {
            String d = values.get("hire_date");
            w.setHireDate(d == null ? null : LocalDate.parse(d));
        }
        if (values.containsKey("id_card_number"))  w.setIdCardNumber(values.get("id_card_number"));
        if (values.containsKey("company"))         w.setCompany(values.get("company"));
        if (values.containsKey("clothing_size"))   w.setClothingSize(values.get("clothing_size"));
        if (values.containsKey("clothing_issued")) w.setClothingIssued(values.get("clothing_issued"));
    }

    private static String mapped(CsvRow row, String column, Map<String, String> allowed) {
        String v = row.text(column);
        if (v == null) return null;
        String m = allowed.get(cyrillicWord(v).toLowerCase(Locale.ROOT));
        if (m == null) {
            throw new CsvValueException(row.line(), column, v, ImportErrorCode.INVALID_ENUM,
                "Must be one of: " + String.join(", ", new java.util.TreeSet<>(allowed.keySet())) + ".");
        }
        return m;
    }

    /** A contact cell: a value that fails {@code check} is a note, read as empty with a warning. */
    private static String contact(CsvRow row, String column, int max, RowWarnings warnings,
                                  UnaryOperator<String> check, String what) {
        String v = row.text(column, max);
        if (v == null || SILENT_BLANKS.contains(v)) return null;
        String ok = check.apply(v);
        if (ok == null) {
            warnings.warn(column, "'" + v + "' is " + what + "; read as empty.");
            return null;
        }
        return text(ok);
    }

    private static String plain(CsvRow row, String column, int max) {
        String v = row.text(column, max);
        return v == null || SILENT_BLANKS.contains(v) ? null : text(nfc(v));
    }

    private static String text(String s) {
        return ValueNormalizer.normalize(s, ColumnType.TEXT);
    }

    private static String nfc(String s) {
        return s == null ? null : Normalizer.normalize(s, Normalizer.Form.NFC);
    }

    /**
     * NFC, then Latin look-alikes replaced inside any word that has a Cyrillic letter (and a lone
     * look-alike letter in a Cyrillic value, as in {@code Слави P}). Emails and links never come here.
     */
    static String cyrillic(String s) {
        if (s == null) return null;
        String n = nfc(s);
        if (!CYRILLIC.matcher(n).find()) return n;
        StringBuilder out = new StringBuilder();
        for (String token : n.split("(?<=\\s)|(?=\\s)")) {
            boolean fix = CYRILLIC.matcher(token).find()
                || (token.length() == 1 && HOMOGLYPHS.containsKey(token.charAt(0)));
            out.append(fix ? cyrillicWord(token) : token);
        }
        return out.toString();
    }

    private static String cyrillicWord(String token) {
        StringBuilder b = new StringBuilder(token.length());
        for (char ch : nfc(token).toCharArray()) b.append(HOMOGLYPHS.getOrDefault(ch, ch));
        return b.toString();
    }

    /** How names are compared for adoption: cleaned, whitespace collapsed, case folded. */
    static String matchKey(String name) {
        String c = cyrillic(name);
        return c == null ? "" : c.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
