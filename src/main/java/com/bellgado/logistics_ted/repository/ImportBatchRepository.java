package com.bellgado.logistics_ted.repository;

import com.bellgado.logistics_ted.domain.ImportBatch;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportBatchRepository extends JpaRepository<ImportBatch, Long> {

    Optional<ImportBatch> findByPublicId(UUID publicId);

    Page<ImportBatch> findAllByOrderByStartedAtDesc(Pageable pageable);

    Page<ImportBatch> findByEntityTypeOrderByStartedAtDesc(String entityType, Pageable pageable);
}
