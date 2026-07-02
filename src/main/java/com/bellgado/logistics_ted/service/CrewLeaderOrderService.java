package com.bellgado.logistics_ted.service;

import com.bellgado.logistics_ted.domain.Crew;
import com.bellgado.logistics_ted.domain.HouseStage;
import com.bellgado.logistics_ted.domain.Material;
import com.bellgado.logistics_ted.domain.MaterialOrder;
import com.bellgado.logistics_ted.domain.MaterialOrderItem;
import com.bellgado.logistics_ted.domain.Worker;
import com.bellgado.logistics_ted.domain.WorkerRole;
import com.bellgado.logistics_ted.repository.HouseStageRepository;
import com.bellgado.logistics_ted.repository.MaterialOrderRepository;
import com.bellgado.logistics_ted.repository.MaterialRepository;
import com.bellgado.logistics_ted.repository.WorkerRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Crew-leader material ordering on top of the worker-credential login model.
 *
 * <p>The logged-in crew leader is a {@link Worker} (role {@code CREW_LEADER}) resolved by username.
 * The invariant this class exists to enforce: a leader may only raise an order for a house stage
 * <b>assigned to their own crew</b> ({@code house_stage.crew_id == leader's crew}) — never a free or
 * unrelated order. Ownership is checked server-side, so the client cannot post an arbitrary stage id.
 */
@Service
public class CrewLeaderOrderService {

    private final WorkerRepository workers;
    private final HouseStageRepository stages;
    private final MaterialOrderRepository orders;
    private final MaterialRepository materials;

    public CrewLeaderOrderService(WorkerRepository workers, HouseStageRepository stages,
                                  MaterialOrderRepository orders, MaterialRepository materials) {
        this.workers = workers;
        this.stages = stages;
        this.orders = orders;
        this.materials = materials;
    }

    public record ItemInput(Integer materialId, BigDecimal quantity) {}

    /** The (house, stage) cells assigned to the leader's crew — the only things they may order for. */
    @Transactional(readOnly = true)
    public List<HouseStage> assignmentsFor(String username) {
        return stages.findByCrewIdWithHouse(requireCrew(username).getId());
    }

    @Transactional(readOnly = true)
    public List<MaterialOrder> ordersFor(String username) {
        return orders.findByCreatedByWithDetails(requireLeader(username).getId());
    }

    @Transactional
    public MaterialOrder create(String username, Integer houseStageId, String notes, List<ItemInput> items) {
        Worker leader = requireLeader(username);
        HouseStage stage = requireOwnedStage(username, houseStageId);
        if (items == null || items.isEmpty()) {
            throw badRequest("At least one material is required.");
        }

        MaterialOrder order = new MaterialOrder();
        order.setHouse(stage.getHouse());
        order.setHouseStage(stage);
        order.setCreatedBy(leader);
        order.setStatus("DRAFT");
        order.setNotes(notes);
        for (ItemInput in : items) {
            if (in.materialId() == null || in.quantity() == null || in.quantity().signum() <= 0) {
                throw badRequest("Each item needs a material and a positive quantity.");
            }
            Material material = materials.findById(in.materialId())
                .orElseThrow(() -> badRequest("Material not found: " + in.materialId()));
            MaterialOrderItem item = new MaterialOrderItem();
            item.setOrder(order);
            item.setMaterial(material);
            item.setQuantity(in.quantity());
            order.getItems().add(item);
        }
        return orders.save(order);
    }

    /** Mark a stage started (status IN_PROGRESS, stamp start date if unset). Ownership-checked. */
    @Transactional
    public HouseStage startStage(String username, Integer houseStageId) {
        HouseStage stage = requireOwnedStage(username, houseStageId);
        if (stage.getStartDate() == null) {
            stage.setStartDate(LocalDate.now());
        }
        stage.setStatus("IN_PROGRESS");
        stage.setUpdatedAt(LocalDateTime.now());
        return stages.save(stage);
    }

    /** Mark a stage finished (status DONE, stamp end date). Ownership-checked. */
    @Transactional
    public HouseStage finishStage(String username, Integer houseStageId) {
        HouseStage stage = requireOwnedStage(username, houseStageId);
        if (stage.getStartDate() == null) {
            stage.setStartDate(LocalDate.now());
        }
        stage.setEndDate(LocalDate.now());
        stage.setStatus("DONE");
        stage.setUpdatedAt(LocalDateTime.now());
        return stages.save(stage);
    }

    /** Load a stage and assert it belongs to the caller's crew — the ownership gate. */
    private HouseStage requireOwnedStage(String username, Integer houseStageId) {
        Crew crew = requireCrew(username);
        if (houseStageId == null) {
            throw badRequest("A house stage is required.");
        }
        HouseStage stage = stages.findById(houseStageId)
            .orElseThrow(() -> badRequest("Stage not found."));
        if (stage.getCrewId() == null || !stage.getCrewId().equals(crew.getId())) {
            throw forbidden("This house/stage is not assigned to your crew.");
        }
        return stage;
    }

    private Worker requireLeader(String username) {
        Worker w = workers.findByUsername(username)
            .orElseThrow(() -> forbidden("Unknown crew login."));
        if (w.getRole() != WorkerRole.CREW_LEADER) {
            throw forbidden("This account is not a crew leader.");
        }
        return w;
    }

    private Crew requireCrew(String username) {
        Crew crew = requireLeader(username).getCrew();
        if (crew == null) {
            throw forbidden("Your account is not attached to a crew.");
        }
        return crew;
    }

    private static ResponseStatusException badRequest(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    private static ResponseStatusException forbidden(String msg) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, msg);
    }
}
