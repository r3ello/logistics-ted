package com.bellgado.logistics_ted.service.importer.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bellgado.logistics_ted.config.WorkerCredentialSeeder;
import com.bellgado.logistics_ted.domain.Crew;
import com.bellgado.logistics_ted.domain.EmploymentType;
import com.bellgado.logistics_ted.domain.Worker;
import com.bellgado.logistics_ted.domain.WorkerRole;
import com.bellgado.logistics_ted.repository.CrewRepository;
import com.bellgado.logistics_ted.repository.HouseStageRepository;
import com.bellgado.logistics_ted.repository.WorkerRepository;
import com.bellgado.logistics_ted.service.CrewMembershipService;
import com.bellgado.logistics_ted.service.importer.EntityImporter;
import com.bellgado.logistics_ted.service.importer.ImportErrorCode;
import com.bellgado.logistics_ted.web.importer.csv.CsvFormat;
import com.bellgado.logistics_ted.web.importer.csv.CsvReader;
import com.bellgado.logistics_ted.web.importer.csv.CsvRow;
import com.bellgado.logistics_ted.web.importer.csv.CsvTable;
import com.bellgado.logistics_ted.web.importer.csv.CsvValueException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The staff sheet importer against in-memory worker/crew stores, with the real
 * {@link CrewMembershipService} — the assertions are about who ends up in which crew and who leads it.
 */
class WorkerImporterTest {

    /** The client's header, verbatim, including the columns that must be dropped. */
    private static final String SHEET_HEADER =
        "ID,Група,Бригада Име,Име Служител,Роля,Вид служител,Телефон,Имейл,снимки,Локация,"
        + "Дата Постъпване,Лична Карта,Фирма,Осигурен,от Фирма,от Служител,Размер Дрехи,Облекло";

    private final Map<Integer, Worker> workerStore = new LinkedHashMap<>();
    private final Map<Integer, Crew> crewStore = new LinkedHashMap<>();
    private final AtomicInteger ids = new AtomicInteger(400);
    private final List<String> leaderNameSyncs = new ArrayList<>();
    private WorkerImporter importer;

    @BeforeEach
    void setUp() {
        WorkerRepository workers = mock(WorkerRepository.class);
        when(workers.save(any(Worker.class))).thenAnswer(inv -> {
            Worker w = inv.getArgument(0);
            if (w.getId() == null) w.setId(ids.incrementAndGet());
            workerStore.put(w.getId(), w);
            return w;
        });
        when(workers.findById(any())).thenAnswer(inv -> Optional.ofNullable(workerStore.get(inv.<Integer>getArgument(0))));
        when(workers.findAll()).thenAnswer(inv -> new ArrayList<>(workerStore.values()));
        when(workers.findByExternalId(any())).thenAnswer(inv -> workerStore.values().stream()
            .filter(w -> inv.getArgument(0).equals(w.getExternalId())).findFirst());
        when(workers.findByExternalIdIsNull()).thenAnswer(inv -> workerStore.values().stream()
            .filter(w -> w.getExternalId() == null).toList());

        CrewRepository crews = mock(CrewRepository.class);
        when(crews.save(any(Crew.class))).thenAnswer(inv -> {
            Crew c = inv.getArgument(0);
            if (c.getId() == null) c.setId(ids.incrementAndGet());
            crewStore.put(c.getId(), c);
            return c;
        });
        when(crews.findByStageGroupAndNickname(any(), any())).thenAnswer(inv -> crewStore.values().stream()
            .filter(c -> inv.getArgument(0).equals(c.getStageGroup()) && inv.getArgument(1).equals(c.getNickname()))
            .findFirst());
        when(crews.findByExternalId(any())).thenAnswer(inv -> crewStore.values().stream()
            .filter(c -> inv.getArgument(0).equals(c.getExternalId())).findFirst());

        HouseStageRepository houseStages = mock(HouseStageRepository.class);
        doAnswer(inv -> { leaderNameSyncs.add(inv.getArgument(0) + ":" + inv.getArgument(1)); return null; })
            .when(houseStages).syncLeaderNameForCrew(any(), any());

        WorkerCredentialSeeder seeder = mock(WorkerCredentialSeeder.class);
        doAnswer(inv -> { inv.<Worker>getArgument(0).setUsername("u" + ids.get()); return null; })
            .when(seeder).assignCredentials(any(Worker.class));

        Map<String, List<Integer>> groups = Map.of(
            "конструкция", List.of(2),
            "покрив",      List.of(3, 4, 5, 6, 19),
            "изолация",    List.of(13, 18),
            "картон",      List.of(14, 15, 21),
            "вик",         List.of(12));
        importer = new WorkerImporter(workers, crews, new CrewMembershipService(crews, workers, houseStages),
            seeder, () -> groups);
    }

    private CsvRow row(String data) {
        CsvTable t = CsvReader.parse(SHEET_HEADER + "\n" + data + "\n", CsvFormat.defaults())
            .renameHeaders(importer.headerResolver());
        return t.rows().get(0);
    }

    private Map<String, String> read(String data) {
        return importer.readRow(row(data), (code, c, m) -> { });
    }

    private Worker seeded(String name, WorkerRole role, Crew crew) {
        Worker w = new Worker();
        w.setId(ids.incrementAndGet());
        w.setName(name);
        w.setLocation("София");
        w.setRole(role);
        w.setCrew(crew);
        w.setUsername(name.split(" ")[0].toLowerCase());
        w.setStageOrders(new ArrayList<>(crew == null ? List.of() : crew.getStageOrders()));
        workerStore.put(w.getId(), w);
        if (crew != null && role == WorkerRole.CREW_LEADER) crew.setLeader(w);
        return w;
    }

    private Crew seededCrew(String name, Integer... stages) {
        Crew c = new Crew();
        c.setId(ids.incrementAndGet());
        c.setName(name);
        c.setStageOrders(new ArrayList<>(List.of(stages)));
        crewStore.put(c.getId(), c);
        return c;
    }

    private Worker import_(String data) {
        Map<String, String> v = read(data);
        String key = data.substring(0, data.indexOf(','));
        EntityImporter.Adoption a = importer.adopt(key, v, true);
        if (a == null) return workerStore.get(importer.create(key, v).intValue());
        Map<String, String> app = importer.project(a.entityId());
        Map<String, String> changed = new LinkedHashMap<>();
        v.forEach((k, val) -> { if (!Objects.equals(val, app.get(k))) changed.put(k, val); });
        importer.update(a.entityId(), changed);
        return workerStore.get(a.entityId().intValue());
    }

    /** The new version of the sheet: a CrewID column first, filled on leaders' rows only. */
    private static final String CREW_ID_HEADER = "CrewID," + SHEET_HEADER;

    private Worker importWithCrewId(String data) {
        CsvRow r = CsvReader.parse(CREW_ID_HEADER + "\n" + data + "\n", CsvFormat.defaults())
            .renameHeaders(importer.headerResolver()).rows().get(0);
        Map<String, String> v = importer.readRow(r, (code, c, m) -> { });
        String key = data.split(",", -1)[1];
        EntityImporter.Adoption a = importer.adopt(key, v, true);
        if (a == null) return workerStore.get(importer.create(key, v).intValue());
        Map<String, String> app = importer.project(a.entityId());
        Map<String, String> changed = new LinkedHashMap<>();
        v.forEach((k, val) -> { if (!Objects.equals(val, app.get(k))) changed.put(k, val); });
        importer.update(a.entityId(), changed);
        return workerStore.get(a.entityId().intValue());
    }

    // ── reading the client's sheet ────────────────────────────────────────────

    @Test
    void readsTheClientSheetAsIsAndDropsTheInsuranceAmounts() {
        CsvTable t = CsvReader.parse(SHEET_HEADER + "\n"
                + "FR001,Конструкция,Влади,Владимир Борисов,Главен,Подизпълнител,0888 116 422,"
                + "v@example.com,https://drive.google.com/file/d/x/view,Благоевград,04.03.2024,"
                + "123456789,Тимбърбилд,480,300,180,XXL,5 Тениски; 2 Елека\n", CsvFormat.defaults())
            .renameHeaders(importer.headerResolver());

        assertThat(t.unknownColumns(new java.util.HashSet<>(importer.columns().keySet()) {{ add("key"); }}))
            .containsExactlyInAnyOrder("осигурен", "от фирма", "от служител");

        Map<String, String> v = importer.readRow(t.rows().get(0), (code, c, m) -> { });
        assertThat(v).containsEntry("name", "Владимир Борисов")
            .containsEntry("stage_group", "Конструкция")
            .containsEntry("crew", "Влади")
            .containsEntry("role", "CREW_LEADER")
            .containsEntry("employment_type", "SUBCONTRACTOR")
            .containsEntry("phone", "0888116422")
            .containsEntry("hire_date", "2024-03-04")
            .containsEntry("id_card_number", "123456789")
            .containsEntry("company", "Тимбърбилд")
            .containsEntry("clothing_size", "XXL")
            .containsEntry("clothing_issued", "5 Тениски; 2 Елека")
            .doesNotContainKeys("осигурен", "от фирма", "от служител");
    }

    @Test
    void latinLookAlikesAndDecomposedLettersAreNormalised() {
        // "Eвгени" with a Latin E, and "Николай" typed as и + combining breve.
        Map<String, String> v = read("FR023,Конструкция,Eвгени,Николай Митов,Служител,Вътрешен,,,,,,,,,,,,");

        assertThat(v.get("crew")).isEqualTo("Евгени").matches("\\p{IsCyrillic}+");
        assertThat(v.get("name")).isEqualTo("Николай Митов");
    }

    @Test
    void notesInContactCellsAreReadAsEmptyWithAWarning() {
        List<String> warned = new ArrayList<>();
        Map<String, String> v = importer.readRow(
            row("RO017,Покрив,Борко,Боян Славчев,Служител,Вътрешен,няма,Иска да говори с Теодор,Трябва да ги сложа,.,,,,,,,,"),
            (code, c, m) -> warned.add(c));

        assertThat(v.get("phone")).isNull();
        assertThat(v.get("email")).isNull();
        assertThat(v.get("photo_url")).isNull();
        assertThat(v.get("location")).isNull();
        assertThat(warned).containsExactly("phone", "email", "photo_url");   // "." is silent
    }

    @Test
    void aRowWithoutARealNameFails() {
        assertThatThrownBy(() -> read("RO017,Покрив,Борко,1,Служител,Подизпълнител,,,,,,,,,,,,"))
            .isInstanceOf(CsvValueException.class)
            .extracting(e -> ((CsvValueException) e).getCode()).isEqualTo(ImportErrorCode.EMPTY_REQUIRED);
        assertThatThrownBy(() -> read("IS019,Изолация/ Картон,Любо,,Главен,Външен,,,,,,,,,,,,"))
            .isInstanceOf(CsvValueException.class);
    }

    @Test
    void combinedGroupsAreCanonicalAndUnknownGroupsFail() {
        assertThat(read("IS001,Изолация/ Картон,Слави Р,Слави Ризов,Главен,Вътрешен,,,,,,,,,,,,"))
            .containsEntry("stage_group", "Изолация/Картон");
        assertThatThrownBy(() -> read("X001,Фасада,Паскалев,Иван Паскалев,Главен,Вътрешен,,,,,,,,,,,,"))
            .isInstanceOf(CsvValueException.class)
            .extracting(e -> ((CsvValueException) e).getCode()).isEqualTo(ImportErrorCode.INVALID_ENUM);
    }

    @Test
    void roleAndTypeAreValidated() {
        assertThatThrownBy(() -> read("FR001,Конструкция,Влади,Владимир Борисов,Шеф,Вътрешен,,,,,,,,,,,,"))
            .isInstanceOf(CsvValueException.class);
        assertThatThrownBy(() -> read("FR001,Конструкция,Влади,Владимир Борисов,Главен,Друг,,,,,,,,,,,,"))
            .isInstanceOf(CsvValueException.class);
    }

    // ── creating people and crews ─────────────────────────────────────────────

    @Test
    void aNewCrewIsCreatedWithTheGroupsStagesAndItsLeader() {
        Worker leader = import_("FR012,Конструкция,Мартин,Мартин Ризов,Главен,Вътрешен,,,,Самоков,,,,,,,,");
        Worker member = import_("FR013,Конструкция,Мартин,Илия Спасов,Служител,Вътрешен,0878736711,,,,,,,,,,,");

        Crew crew = leader.getCrew();
        assertThat(crew.getStageGroup()).isEqualTo("Конструкция");
        assertThat(crew.getNickname()).isEqualTo("Мартин");
        assertThat(crew.getName()).isEqualTo("Мартин Ризов");
        assertThat(crew.getStageOrders()).containsExactly(2);
        assertThat(crew.getLeader()).isSameAs(leader);
        assertThat(crew.getLocation()).isEqualTo("Самоков");
        assertThat(member.getCrew()).isSameAs(crew);
        assertThat(member.getStageOrders()).containsExactly(2);
        assertThat(leader.getExternalId()).isEqualTo("FR012");
        assertThat(leader.getUsername()).isNotNull();                    // gets a login like a UI-created worker
        assertThat(leaderNameSyncs).contains(crew.getId() + ":Мартин Ризов");
    }

    @Test
    void theSameNicknameInTwoGroupsIsTwoCrews() {
        Worker roof = import_("RO001,Покрив,Любо,Любомир Стоичков,Главен,Вътрешен,,,,,,,,,,,,");
        Worker insulation = import_("IS018,Изолация/Картон,Любо,Любомир Фарашев,Главен,Подизпълнител,,,,,,,,,,,,");

        assertThat(roof.getCrew()).isNotSameAs(insulation.getCrew());
        assertThat(insulation.getCrew().getStageOrders()).containsExactly(13, 18, 14, 15, 21);
        assertThat(insulation.getEmploymentType()).isEqualTo(EmploymentType.SUBCONTRACTOR);
    }

    // ── adopting the seeded workers and crews ─────────────────────────────────

    @Test
    void aSeededWorkerIsAdoptedByNameAndTheirCrewGetsTheSheetIdentity() {
        Crew crew = seededCrew("Владимир Борисов", 2);
        Worker vladi = seeded("Владимир Борисов", WorkerRole.CREW_LEADER, crew);
        Worker daniel = seeded("Даниел Угринов", WorkerRole.CREW_MEMBER, crew);

        import_("FR001,Конструкция,Влади,Владимир Борисов,Главен,Подизпълнител,0888116422,,,,,,,,,,,");
        import_("FR002,Конструкция,Влади,Даниел Угринов,Служител,Външен,,,,,,,,,,,,");

        assertThat(workerStore).hasSize(2);                               // nobody duplicated
        assertThat(crewStore).hasSize(1);
        assertThat(crew.getNickname()).isEqualTo("Влади");
        assertThat(crew.getStageGroup()).isEqualTo("Конструкция");
        assertThat(crew.getLeader()).isSameAs(vladi);
        assertThat(vladi.getExternalId()).isEqualTo("FR001");
        assertThat(vladi.getPhone()).isEqualTo("0888116422");
        assertThat(vladi.getUsername()).isEqualTo("владимир");          // credentials untouched
        assertThat(daniel.getCrew()).isSameAs(crew);
        assertThat(daniel.getEmploymentType()).isEqualTo(EmploymentType.EXTERNAL);
    }

    @Test
    void validateRecognisesTheSeededWorkerWithoutWriting() {
        Worker w = seeded("Даниел Угринов", WorkerRole.CREW_MEMBER, seededCrew("Владимир Борисов", 2));

        EntityImporter.Adoption a = importer.adopt("FR002",
            read("FR002,Конструкция,Влади,Даниел Угринов,Служител,Външен,,,,,,,,,,,,"), false);

        assertThat(a.entityId()).isEqualTo(Long.valueOf(w.getId()));
        assertThat(a.reason()).contains("same name");
        assertThat(w.getExternalId()).isNull();
    }

    @Test
    void twoNamesakesAreToldApartByCrewOrRejected() {
        Crew roof = seededCrew("Любомир Стоичков", 3, 4, 5, 6, 19);
        Crew plumbing = seededCrew("Киро", 12);
        Worker roofer = seeded("Мартин Павлов", WorkerRole.CREW_MEMBER, roof);
        seeded("Мартин Павлов", WorkerRole.CREW_MEMBER, plumbing);

        EntityImporter.Adoption a = importer.adopt("RO099",
            read("RO099,Покрив,Любо,Мартин Павлов,Служител,Вътрешен,,,,,,,,,,,,"), false);
        assertThat(a.entityId()).isEqualTo(Long.valueOf(roofer.getId()));

        assertThatThrownBy(() -> importer.adopt("FR014",
                read("FR014,Конструкция,Мартин,Мартин Павлов,Служител,Вътрешен,,,,,,,,,,,,"), false))
            .isInstanceOf(CsvValueException.class)
            .extracting(e -> ((CsvValueException) e).getCode()).isEqualTo(ImportErrorCode.AMBIGUOUS_MATCH);
    }

    @Test
    void aRenamedLeaderRenamesTheirCrewAndItsStages() {
        Crew crew = seededCrew("Слави Василев", 13, 18);
        Worker slavi = seeded("Слави Василев", WorkerRole.CREW_LEADER, crew);
        slavi.setExternalId("IS008");

        import_("IS008,Изолация/Картон,Слави В,Слави Веселинов,Главен,Вътрешен,,,,,,,,,,,,");

        assertThat(slavi.getName()).isEqualTo("Слави Веселинов");
        assertThat(crew.getName()).isEqualTo("Слави Веселинов");
        assertThat(leaderNameSyncs).contains(crew.getId() + ":Слави Веселинов");
    }

    @Test
    void movingToAnotherCrewHandsOverTheOldLeadership() {
        Worker w = import_("WS007,ВиК,Киро,Кирил Пендов,Главен,Вътрешен,,,,,,,,,,,,");
        Crew old = w.getCrew();

        import_("WS007,ВиК,Ники,Кирил Пендов,Служител,Вътрешен,,,,,,,,,,,,");

        assertThat(old.getLeader()).isNull();
        assertThat(w.getCrew().getNickname()).isEqualTo("Ники");
        assertThat(w.getRole()).isEqualTo(WorkerRole.CREW_MEMBER);
    }

    // ── CrewID ────────────────────────────────────────────────────────────────

    @Test
    void theLeadersCrewIdIsStampedOnTheCrewAndMembersStillJoinByNickname() {
        Worker leader = importWithCrewId("CRIS006,IS022,Изолация/ Картон,Виктор,Виктор Петков,Главен,Вътрешен,,,,,,,,,,,,");
        Worker member = importWithCrewId(",IS023,Изолация/ Картон,Виктор,Радослав Димитров,Служител,Вътрешен,,,,,,,,,,,,");

        Crew crew = leader.getCrew();
        assertThat(crew.getExternalId()).isEqualTo("CRIS006");
        assertThat(crew.getNickname()).isEqualTo("Виктор");
        assertThat(member.getCrew()).isSameAs(crew);
        assertThat(importer.project(Long.valueOf(member.getId()))).containsEntry("crew_ref", "CRIS006");
    }

    @Test
    void anEmptyCrewIdCellIsNotAField() {
        CsvRow r = CsvReader.parse(CREW_ID_HEADER + "\n"
                + ",IS023,Изолация/ Картон,Виктор,Радослав Димитров,Служител,Вътрешен,,,,,,,,,,,,\n", CsvFormat.defaults())
            .renameHeaders(importer.headerResolver()).rows().get(0);
        assertThat(importer.readRow(r, (code, c, m) -> { })).doesNotContainKey("crew_ref");
    }

    @Test
    void anExistingCrewFoundByNicknameGetsTheCrewId() {
        import_("IS022,Изолация/Картон,Виктор,Виктор Петков,Главен,Вътрешен,,,,,,,,,,,,");
        Crew crew = crewStore.values().iterator().next();
        assertThat(crew.getExternalId()).isNull();

        importWithCrewId("CRIS006,IS022,Изолация/ Картон,Виктор,Виктор Петков,Главен,Вътрешен,,,,,,,,,,,,");

        assertThat(crewStore).hasSize(1);
        assertThat(crew.getExternalId()).isEqualTo("CRIS006");
    }

    @Test
    void aRenamedNicknameRenamesTheCrewInsteadOfMakingANewOne() {
        Worker leader = importWithCrewId("CRIS001,IS001,Изолация/ Картон,Слави Р,Слави Ризов,Главен,Вътрешен,,,,,,,,,,,,");
        Worker member = importWithCrewId(",IS002,Изолация/ Картон,Слави Р,Митко Янев,Служител,Вътрешен,,,,,,,,,,,,");
        Crew crew = leader.getCrew();

        // The client renames the nickname on every row; the CrewID on the leader's row is unchanged.
        importWithCrewId("CRIS001,IS001,Изолация/ Картон,Слави,Слави Ризов,Главен,Вътрешен,,,,,,,,,,,,");
        importWithCrewId(",IS002,Изолация/ Картон,Слави,Митко Янев,Служител,Вътрешен,,,,,,,,,,,,");

        assertThat(crewStore).hasSize(1);
        assertThat(crew.getNickname()).isEqualTo("Слави");
        assertThat(crew.getExternalId()).isEqualTo("CRIS001");
        assertThat(crew.getLeader()).isSameAs(leader);
        assertThat(member.getCrew()).isSameAs(crew);
    }

    @Test
    void aGroupRespeltInTheSheetKeepsTheSameCrew() {
        // 2026-10-16 regression: only the group's case changed, so the unchanged CrewID was not among
        // the changed columns — the crew must still be found by it, not duplicated by the new pair.
        Worker leader = importWithCrewId("CRWS003,WS007,ВиК,Киро,Кирил Пендов,Главен,Вътрешен,,,,,,,,,,,,");
        Crew crew = leader.getCrew();

        importWithCrewId("CRWS003,WS007,вик,Киро,Кирил Пендов,Главен,Вътрешен,,,,,,,,,,,,");

        assertThat(crewStore).hasSize(1);
        assertThat(leader.getCrew()).isSameAs(crew);
        assertThat(crew.getStageGroup()).isEqualTo("вик");
        assertThat(crew.getLeader()).isSameAs(leader);
    }

    @Test
    void aCrewIdThatWouldMergeTwoCrewsIsRejected() {
        importWithCrewId("CRIS006,IS022,Изолация/ Картон,Виктор,Виктор Петков,Главен,Вътрешен,,,,,,,,,,,,");
        import_("IS014,Изолация/Картон,Иво,Иво Димитров,Главен,Вътрешен,,,,,,,,,,,,");

        // CRIS006 is Виктор's; giving it to Иво's row would fold one crew into the other.
        assertThatThrownBy(() -> importWithCrewId("CRIS006,IS014,Изолация/ Картон,Иво,Иво Димитров,Главен,Вътрешен,,,,,,,,,,,,"))
            .isInstanceOf(CsvValueException.class)
            .extracting(e -> ((CsvValueException) e).getCode()).isEqualTo(ImportErrorCode.KEY_REUSED);
        // ... and a crew that has one CrewID does not silently take another.
        assertThatThrownBy(() -> importWithCrewId("CRIS999,IS022,Изолация/ Картон,Виктор,Виктор Петков,Главен,Вътрешен,,,,,,,,,,,,"))
            .isInstanceOf(CsvValueException.class)
            .extracting(e -> ((CsvValueException) e).getCode()).isEqualTo(ImportErrorCode.KEY_REUSED);
    }

    @Test
    void oneCrewIdOnTwoCrewsInAFileIsADuplicate() {
        CsvTable t = CsvReader.parse(CREW_ID_HEADER + "\n"
                + "CRIS006,IS022,Изолация/ Картон,Виктор,Виктор Петков,Главен,Вътрешен,,,,,,,,,,,,\n"
                + "CRIS006,IS014,Изолация/ Картон,Иво,Иво Димитров,Главен,Вътрешен,,,,,,,,,,,,\n", CsvFormat.defaults())
            .renameHeaders(importer.headerResolver());
        importer.readRow(t.rows().get(0), (code, c, m) -> { });

        assertThatThrownBy(() -> importer.readRow(t.rows().get(1), (code, c, m) -> { }))
            .isInstanceOf(CsvValueException.class)
            .extracting(e -> ((CsvValueException) e).getCode()).isEqualTo(ImportErrorCode.DUPLICATE_KEY);
    }

    // ── Локация as a Google Maps link ─────────────────────────────────────────

    @Test
    void aMapsLinkOnALeadersRowPlacesThePersonAndTheCrewOnTheMap() {
        String link = "https://www.google.com/maps/place/Samokov/@42.328275,23.561096,1053m/data=!3m1!1e3!4m6!3m5"
            + "!1s0x14ab03a2b6cd0e1b:0x400a01269bf5b10!8m2!3d42.3374395!4d23.5527956!16zL20vMDVfaGNo";
        Worker leader = importWithCrewId("CRWS003,WS007,ВиК,Киро,Кирил Пендов,Главен,Вътрешен,,,,\"" + link + "\",,,,,,,,");

        assertThat(leader.getLocation()).isEqualTo("Samokov");
        assertThat(leader.getLat()).isEqualByComparingTo("42.337440");
        assertThat(leader.getLng()).isEqualByComparingTo("23.552796");
        Crew crew = leader.getCrew();
        assertThat(crew.getLocation()).isEqualTo("Samokov");
        assertThat(crew.getLat()).isEqualByComparingTo("42.337440");

        // Same link again: nothing reads as changed (6-decimal rounding matches the column).
        Map<String, String> again = importer.readRow(CsvReader.parse(CREW_ID_HEADER + "\n"
                + "CRWS003,WS007,ВиК,Киро,Кирил Пендов,Главен,Вътрешен,,,,\"" + link + "\",,,,,,,,\n", CsvFormat.defaults())
            .renameHeaders(importer.headerResolver()).rows().get(0), (code, c, m) -> { });
        Map<String, String> app = importer.project(Long.valueOf(leader.getId()));
        again.forEach((k, v) -> assertThat(com.bellgado.logistics_ted.service.importer.ValueNormalizer.eq(v, app.get(k)))
            .as(k).isTrue());
    }

    @Test
    void aShortMapsLinkIsKeptAsTextWithAWarningAndDashesAreEmpty() {
        List<String> warned = new ArrayList<>();
        CsvTable t = CsvReader.parse(CREW_ID_HEADER + "\n"
                + "CRIS006,IS022,Изолация/ Картон,Виктор,Виктор Петков,Главен,Вътрешен,,,,https://maps.app.goo.gl/oaBpZLZwu4dvmw4x6?g_st=av,,,,,,,,\n"
                + "CRWS001,WS001,ВиК,Стоян,Стоян Храдалиев,Главен,Вътрешен,,,,---,,,,,,,,\n", CsvFormat.defaults())
            .renameHeaders(importer.headerResolver());

        Map<String, String> shortLink = importer.readRow(t.rows().get(0), (code, c, m) -> warned.add(code + " " + c));
        Map<String, String> dashes = importer.readRow(t.rows().get(1), (code, c, m) -> warned.add(code + " " + c));

        assertThat(shortLink).containsEntry("location", "https://maps.app.goo.gl/oaBpZLZwu4dvmw4x6?g_st=av")
                             .doesNotContainKeys("lat", "lng");
        assertThat(dashes).containsEntry("location", null);
        assertThat(warned).containsExactly("CELL_IGNORED location");
    }

    @Test
    void aPlainTownLeavesCoordinatesSetInTheAppAlone() {
        assertThat(read("FR001,Конструкция,Влади,Владимир Борисов,Главен,Подизпълнител,,,,София,,,,,,,,"))
            .containsEntry("location", "София").doesNotContainKeys("lat", "lng");
    }

    @Test
    void peopleMissingFromTheFileAreListedNotDeleted() {
        Crew crew = seededCrew("Огнян Пеев", 12);
        Worker ognyan = seeded("Огнян Пеев", WorkerRole.CREW_LEADER, crew);
        Worker kept = import_("WS007,ВиК,Киро,Кирил Пендов,Главен,Вътрешен,,,,,,,,,,,,");

        List<String> missing = importer.notInFile(Set.of(Long.valueOf(kept.getId())));

        assertThat(missing).singleElement().asString().contains("Огнян Пеев").contains(String.valueOf(ognyan.getId()));
        assertThat(workerStore).containsKey(ognyan.getId());
    }
}
