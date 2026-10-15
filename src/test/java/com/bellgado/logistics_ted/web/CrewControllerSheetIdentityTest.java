package com.bellgado.logistics_ted.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bellgado.logistics_ted.domain.Crew;
import com.bellgado.logistics_ted.repository.CrewRepository;
import com.bellgado.logistics_ted.repository.HouseRepository;
import com.bellgado.logistics_ted.repository.HouseStageRepository;
import com.bellgado.logistics_ted.repository.WorkerRepository;
import com.bellgado.logistics_ted.service.CrewMembershipService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** The staff-sheet identity (group + nickname, CrewID) editable on a crew: admin-only, unique, validated. */
class CrewControllerSheetIdentityTest {

    private final Map<Integer, Crew> store = new LinkedHashMap<>();
    private CrewController controller;

    @BeforeEach
    void setUp() {
        CrewRepository crews = mock(CrewRepository.class);
        when(crews.findById(any())).thenAnswer(inv -> Optional.ofNullable(store.get(inv.<Integer>getArgument(0))));
        when(crews.save(any(Crew.class))).thenAnswer(inv -> inv.getArgument(0));
        when(crews.findByStageGroupAndNickname(any(), any())).thenAnswer(inv -> store.values().stream()
            .filter(c -> inv.getArgument(0).equals(c.getStageGroup()) && inv.getArgument(1).equals(c.getNickname()))
            .findFirst());
        when(crews.findByExternalId(any())).thenAnswer(inv -> store.values().stream()
            .filter(c -> inv.getArgument(0).equals(c.getExternalId())).findFirst());

        HouseStageRepository stages = mock(HouseStageRepository.class);
        List<Object[]> groups = List.of(new Object[]{13, "Изолация"}, new Object[]{14, "Картон"},
                                        new Object[]{11, "Ел"}, new Object[]{24, "Ел"});
        when(stages.findCrewStageGroups()).thenReturn(groups);

        controller = new CrewController(crews, mock(WorkerRepository.class), mock(HouseRepository.class), stages,
            mock(JdbcTemplate.class), mock(CrewMembershipService.class));

        store.put(119, crew(119, "Виктор Петков", "Изолация/Картон", "Виктор", "CRIS006"));
        store.put(118, crew(118, "Иво Димитров", "Изолация/Картон", "Иво", "CRIS004"));
        store.put(113, crew(113, "Огнян Пеев", null, null, null));
        loginAs("ROLE_ADMIN");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static Crew crew(int id, String name, String group, String nick, String ext) {
        Crew c = new Crew();
        c.setId(id);
        c.setName(name);
        c.setStageGroup(group);
        c.setNickname(nick);
        c.setExternalId(ext);
        c.setStageOrders(new ArrayList<>());
        return c;
    }

    private static void loginAs(String authority) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            "u", null, List.of(new SimpleGrantedAuthority(authority))));
    }

    private ResponseEntity<?> put(int id, Object... kv) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", store.get(id).getName());
        for (int i = 0; i < kv.length; i += 2) body.put((String) kv[i], kv[i + 1]);
        return controller.update(id, body);
    }

    private static String error(ResponseEntity<?> r) {
        assertThat(r.getStatusCode().value()).isEqualTo(400);
        return String.valueOf(((Map<?, ?>) r.getBody()).get("error"));
    }

    @Test
    void aHandMadeCrewGetsItsSheetIdentity() {
        ResponseEntity<?> r = put(113, "stageGroup", "ВиК", "nickname", "Огнян", "externalId", "CRWS005");

        // ВиК is not a group in this slice of stage_type: rejected, nothing written.
        assertThat(error(r)).contains("Unknown group 'ВиК'");
        assertThat(store.get(113).getNickname()).isNull();

        assertThat(put(113, "stageGroup", "Ел", "nickname", " Огнян ", "externalId", "CREL009").getStatusCode().value())
            .isEqualTo(200);
        Crew c = store.get(113);
        assertThat(c.getStageGroup()).isEqualTo("Ел");
        assertThat(c.getNickname()).isEqualTo("Огнян");
        assertThat(c.getExternalId()).isEqualTo("CREL009");
    }

    @Test
    void groupAndNicknameGoTogether() {
        assertThat(error(put(113, "stageGroup", "Ел", "nickname", ""))).contains("go together");
    }

    @Test
    void thePairAndTheCrewIdAreUnique() {
        assertThat(error(put(118, "stageGroup", "Изолация/ Картон", "nickname", "Виктор"))).contains("#119");
        assertThat(error(put(118, "externalId", "CRIS006"))).contains("CRIS006").contains("#119");
        // Its own values are not a clash.
        assertThat(put(119, "stageGroup", "Изолация/Картон", "nickname", "Виктор", "externalId", "CRIS006")
            .getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void aCaseOnlyEditKeepsTheSheetsSpellingAndLookAlikesAreFixed() {
        put(119, "stageGroup", "изолация/картон", "nickname", "Bиктор");    // Latin B

        assertThat(store.get(119).getStageGroup()).isEqualTo("Изолация/Картон");
        assertThat(store.get(119).getNickname()).isEqualTo("Виктор");
    }

    @Test
    void clearingRemovesTheIdentity() {
        put(119, "stageGroup", "", "nickname", "", "externalId", "");

        Crew c = store.get(119);
        assertThat(c.getStageGroup()).isNull();
        assertThat(c.getNickname()).isNull();
        assertThat(c.getExternalId()).isNull();
    }

    @Test
    void aNonAdminCannotChangeIt() {
        loginAs("ROLE_USER");

        assertThat(put(119, "stageGroup", "Ел", "nickname", "X", "externalId", "HACK").getStatusCode().value())
            .isEqualTo(200);
        Crew c = store.get(119);
        assertThat(c.getNickname()).isEqualTo("Виктор");
        assertThat(c.getExternalId()).isEqualTo("CRIS006");
    }
}
