package com.bellgado.logistics_ted.repository;

import com.bellgado.logistics_ted.domain.ImportRef;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportRefRepository extends JpaRepository<ImportRef, Long> {

    Optional<ImportRef> findByEntityTypeAndExternalKey(String entityType, String externalKey);

    /**
     * Bulk pre-load for one file. A recurring sync resolves every key in the file up-front rather
     * than issuing one query per row — a 800-row {@code house_stages.csv} would otherwise cost 800
     * round trips before doing any work.
     */
    List<ImportRef> findByEntityTypeAndExternalKeyIn(String entityType, Collection<String> externalKeys);

    /** Reverse lookup, used to detect that two keys point at the same row. */
    List<ImportRef> findByEntityTypeAndEntityId(String entityType, Long entityId);

    long countByEntityType(String entityType);
}
