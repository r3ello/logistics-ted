package com.bellgado.logistics_ted.repository;

import com.bellgado.logistics_ted.domain.ImportConflict;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportConflictRepository extends JpaRepository<ImportConflict, Long> {

    /** Re-detection path: an already-open conflict is refreshed, never duplicated. */
    Optional<ImportConflict> findByRefIdAndColumnNameAndResolvedAtIsNull(Long refId, String columnName);

    List<ImportConflict> findByRefIdAndResolvedAtIsNull(Long refId);

    Page<ImportConflict> findByResolvedAtIsNullOrderByDetectedAtDesc(Pageable pageable);

    Page<ImportConflict> findByEntityTypeAndResolvedAtIsNullOrderByDetectedAtDesc(
        String entityType, Pageable pageable);

    long countByResolvedAtIsNull();
}
