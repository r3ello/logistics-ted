package com.bellgado.logistics_ted.service;

import com.bellgado.logistics_ted.domain.Crew;
import com.bellgado.logistics_ted.domain.House;
import com.bellgado.logistics_ted.domain.HouseStage;
import com.bellgado.logistics_ted.domain.Worker;
import com.bellgado.logistics_ted.repository.CrewRepository;
import com.bellgado.logistics_ted.repository.HouseStageRepository;
import com.bellgado.logistics_ted.repository.WorkerRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * The crew-membership rules that the worker and crew screens and the staff import must agree on:
 * who leads a crew, and what joining or leaving one does to a worker's stages.
 *
 * <p>The one side effect that is easy to forget lives here: a crew's {@code house_stage} rows carry
 * the leader's name as text ({@code worker_name}), so every leader change re-syncs it.
 *
 * <p>So does the crew's current house ({@code crew.house_id}): the house where it has a stage
 * {@code IN_PROGRESS}. The stage matrix and the {@code house-stages} import both move it through
 * {@link #crewStartedAt} and {@link #recomputeCrewHouse}.
 *
 * <p>Callers own their transaction (each runs inside a controller's or the import's).
 * {@code @Component}, not {@code @Service}: it takes {@link Worker} entities, which carry credentials
 * and personal data, and {@code ServiceLoggingAspect} renders {@code @Service} arguments at DEBUG.
 */
@Component
public class CrewMembershipService {

    private final CrewRepository crews;
    private final WorkerRepository workers;
    private final HouseStageRepository houseStages;

    public CrewMembershipService(CrewRepository crews, WorkerRepository workers,
                                 HouseStageRepository houseStages) {
        this.crews = crews;
        this.workers = workers;
        this.houseStages = houseStages;
    }

    /**
     * Makes {@code leader} the crew's leader and re-syncs the leader name on the crew's stages.
     *
     * @return the leader it displaced, or {@code null} — what happens to them is the caller's call
     *         (the screens detach them from the crew; the import leaves them where they are)
     */
    public Worker replaceLeader(Crew crew, Worker leader) {
        Worker old = crew.getLeader();
        crew.setLeader(leader);
        crews.save(crew);
        houseStages.syncLeaderNameForCrew(crew.getId(), leader.getName());
        return old != null && !old.getId().equals(leader.getId()) ? old : null;
    }

    /** Leaves the crew without a leader. */
    public void clearLeader(Crew crew) {
        crew.setLeader(null);
        crews.save(crew);
        houseStages.syncLeaderNameForCrew(crew.getId(), null);
    }

    /** Re-syncs the leader name on the crew's stages after the leader was renamed. */
    public void leaderRenamed(Crew crew, String name) {
        houseStages.syncLeaderNameForCrew(crew.getId(), name);
    }

    /** Puts the worker in the crew; their stages become the crew's. */
    public void join(Worker w, Crew crew) {
        w.setCrew(crew);
        w.setStageOrders(new ArrayList<>(crew.getStageOrders()));
        workers.save(w);
    }

    /** Takes the worker out of their crew; they keep no stages. */
    public void leave(Worker w) {
        w.setCrew(null);
        w.setStageOrders(new ArrayList<>());
        workers.save(w);
    }

    /** A stage of this house went {@code IN_PROGRESS} under the crew: that is now where it works. */
    public void crewStartedAt(Integer crewId, House house) {
        crews.findById(crewId).ifPresent(crew -> {
            crew.setHouse(house);
            crews.save(crew);
            // worker.house is derived from crew.house — no direct field to sync
        });
    }

    /**
     * After the crew left a stage (reassigned, or no longer {@code IN_PROGRESS}): keeps its current
     * house while it still has a stage in progress there, else moves it to another house where it
     * does, else clears it.
     *
     * @param excludeStageId a stage to ignore, for a caller that has not saved its change yet; or null
     */
    public void recomputeCrewHouse(Integer crewId, Integer excludeStageId) {
        crews.findById(crewId).ifPresent(crew -> {
            List<HouseStage> working = houseStages.findByCrewIdWithHouse(crewId).stream()
                .filter(s -> "IN_PROGRESS".equals(s.getStatus()) && !Objects.equals(s.getId(), excludeStageId))
                .toList();
            Integer current = crew.getHouse() == null ? null : crew.getHouse().getId();
            boolean stillThere = working.stream().anyMatch(s -> s.getHouse().getId().equals(current));
            crew.setHouse(stillThere ? crew.getHouse() : working.isEmpty() ? null : working.get(0).getHouse());
            crews.save(crew);
        });
    }
}
