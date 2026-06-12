package com.bellgado.logistics_ted.repository;

import com.bellgado.logistics_ted.domain.WorkSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface WorkSessionRepository extends JpaRepository<WorkSession, Integer> {

    /** Today's open session for a worker at a house (not yet checked out). */
    @Query("""
        SELECT s FROM WorkSession s
        WHERE s.worker.id = :workerId
          AND s.house.id  = :houseId
          AND s.sessionDate = :today
        """)
    Optional<WorkSession> findTodaySession(@Param("workerId") Integer workerId,
                                           @Param("houseId")  Integer houseId,
                                           @Param("today")    LocalDate today);

    /** Any session from this device at this house today (to block multi-worker abuse). */
    @Query("""
        SELECT s FROM WorkSession s
        WHERE s.deviceId = :deviceId
          AND s.house.id = :houseId
          AND s.sessionDate = :today
        """)
    Optional<WorkSession> findTodaySessionByDevice(@Param("deviceId") String deviceId,
                                                    @Param("houseId")  Integer houseId,
                                                    @Param("today")    LocalDate today);

    /** All sessions for a crew on a given date (via crew→worker join). */
    @Query("""
        SELECT s FROM WorkSession s
        JOIN FETCH s.worker w
        JOIN FETCH s.house  h
        WHERE w.crew.id = :crewId
          AND s.sessionDate = :date
        ORDER BY s.checkedInAt
        """)
    List<WorkSession> findByCrewAndDate(@Param("crewId") Integer crewId,
                                        @Param("date")   LocalDate date);

    /** All sessions for a worker between two dates. */
    @Query("""
        SELECT s FROM WorkSession s
        JOIN FETCH s.worker w
        JOIN FETCH s.house h
        WHERE s.worker.id = :workerId
          AND s.sessionDate BETWEEN :from AND :to
        ORDER BY s.checkedInAt DESC
        """)
    List<WorkSession> findByWorkerAndRange(@Param("workerId") Integer workerId,
                                           @Param("from")     LocalDate from,
                                           @Param("to")       LocalDate to);
}
