package com.bellgado.logistics_ted.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bellgado.logistics_ted.domain.Crew;
import com.bellgado.logistics_ted.domain.House;
import com.bellgado.logistics_ted.domain.HouseStage;
import com.bellgado.logistics_ted.domain.Material;
import com.bellgado.logistics_ted.domain.MaterialOrder;
import com.bellgado.logistics_ted.domain.Worker;
import com.bellgado.logistics_ted.domain.WorkerRole;
import com.bellgado.logistics_ted.repository.HouseStageRepository;
import com.bellgado.logistics_ted.repository.MaterialOrderRepository;
import com.bellgado.logistics_ted.repository.MaterialRepository;
import com.bellgado.logistics_ted.repository.WorkerRepository;
import com.bellgado.logistics_ted.service.CrewLeaderOrderService.ItemInput;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Pure unit test — no Spring context, no Postgres. Proves the invariant: a crew leader can only raise
 * an order for a house stage assigned to their OWN crew, never a free/unrelated order.
 */
@ExtendWith(MockitoExtension.class)
class CrewLeaderOrderServiceTest {

    private static final String LEADER = "stoyan";

    @Mock WorkerRepository workers;
    @Mock HouseStageRepository stages;
    @Mock MaterialOrderRepository orders;
    @Mock MaterialRepository materials;

    CrewLeaderOrderService service;

    Crew myCrew;
    Worker leader;
    HouseStage ownedStage;
    HouseStage foreignStage;

    @BeforeEach
    void setUp() {
        service = new CrewLeaderOrderService(workers, stages, orders, materials);

        myCrew = new Crew();
        myCrew.setId(10);
        leader = new Worker();
        leader.setId(5);
        leader.setRole(WorkerRole.CREW_LEADER);
        leader.setCrew(myCrew);

        House house = new House();
        house.setId(3);
        house.setName("Хераково");

        ownedStage = new HouseStage();
        ownedStage.setId(1);
        ownedStage.setCrewId(10);          // belongs to myCrew
        ownedStage.setHouse(house);
        ownedStage.setStageName("Улуци");

        foreignStage = new HouseStage();
        foreignStage.setId(2);
        foreignStage.setCrewId(99);        // different crew
        foreignStage.setHouse(house);
    }

    @Test
    void rejectsStageNotAssignedToLeadersCrew() {
        when(workers.findByUsername(LEADER)).thenReturn(Optional.of(leader));
        when(stages.findById(2)).thenReturn(Optional.of(foreignStage));

        assertThatThrownBy(() ->
            service.create(LEADER, 2, null, List.of(new ItemInput(7, BigDecimal.TEN))))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));

        verify(orders, never()).save(any());
    }

    @Test
    void rejectsMissingStage() {
        when(workers.findByUsername(LEADER)).thenReturn(Optional.of(leader));

        assertThatThrownBy(() ->
            service.create(LEADER, null, null, List.of(new ItemInput(7, BigDecimal.TEN))))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        verify(orders, never()).save(any());
    }

    @Test
    void rejectsNonLeaderAccount() {
        Worker member = new Worker();
        member.setId(8);
        member.setRole(WorkerRole.CREW_MEMBER);
        when(workers.findByUsername("ivan")).thenReturn(Optional.of(member));

        assertThatThrownBy(() ->
            service.create("ivan", 1, null, List.of(new ItemInput(7, BigDecimal.ONE))))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void createsOrderBoundToOwnedStageWithLeaderAsCreator() {
        Material plywood = new Material();
        plywood.setId(7);
        plywood.setName("Plywood");

        when(workers.findByUsername(LEADER)).thenReturn(Optional.of(leader));
        when(stages.findById(1)).thenReturn(Optional.of(ownedStage));
        when(materials.findById(7)).thenReturn(Optional.of(plywood));
        when(orders.save(any(MaterialOrder.class))).thenAnswer(inv -> inv.getArgument(0));

        MaterialOrder result = service.create(LEADER, 1, "need for gutters",
            List.of(new ItemInput(7, new BigDecimal("12.5"))));

        assertThat(result.getHouseStage()).isSameAs(ownedStage);
        assertThat(result.getHouse()).isSameAs(ownedStage.getHouse());
        assertThat(result.getCreatedBy()).isSameAs(leader);
        assertThat(result.getStatus()).isEqualTo("DRAFT");
        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().get(0).getQuantity()).isEqualByComparingTo("12.5");
        verify(orders).save(any(MaterialOrder.class));
    }
}
