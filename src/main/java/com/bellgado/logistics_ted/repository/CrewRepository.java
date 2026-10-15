package com.bellgado.logistics_ted.repository;

import com.bellgado.logistics_ted.domain.Crew;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CrewRepository extends JpaRepository<Crew, Integer> {
    List<Crew> findByManagerId(Integer managerId);

    /** A crew by its staff-sheet identity, e.g. ({@code Конструкция}, {@code Влади}) — Flyway V21. */
    Optional<Crew> findByStageGroupAndNickname(String stageGroup, String nickname);

    /** A crew by the staff sheet's CrewID ({@code CRIS006}) — Flyway V22. */
    Optional<Crew> findByExternalId(String externalId);
}
