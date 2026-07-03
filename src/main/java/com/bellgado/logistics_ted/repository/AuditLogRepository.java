package com.bellgado.logistics_ted.repository;

import com.bellgado.logistics_ted.domain.AuditLog;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    // Nullable-param guards trip PostgreSQL's type inference ("could not determine data type
    // of parameter"): CAST(:username AS string) fixes the string params, and the timestamp
    // params take no null check at all — AuditLogService substitutes sentinel bounds
    // (EPOCH / far future) when the caller passes no date filter.
    @Query("""
        SELECT a FROM AuditLog a
        WHERE (:username IS NULL OR LOWER(a.username) LIKE LOWER(CONCAT('%', CAST(:username AS string), '%')))
          AND (:action IS NULL OR a.action = :action)
          AND (:entityType IS NULL OR a.entityType = :entityType)
          AND a.at >= :from
          AND a.at < :to
        ORDER BY a.at DESC, a.id DESC
        """)
    Page<AuditLog> search(@Param("username") String username,
                          @Param("action") String action,
                          @Param("entityType") String entityType,
                          @Param("from") Instant from,
                          @Param("to") Instant to,
                          Pageable pageable);
}
