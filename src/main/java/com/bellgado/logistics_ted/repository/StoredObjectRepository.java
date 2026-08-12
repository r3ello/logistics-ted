package com.bellgado.logistics_ted.repository;

import com.bellgado.logistics_ted.domain.StoredObject;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface StoredObjectRepository extends JpaRepository<StoredObject, Long> {

    Optional<StoredObject> findByPublicId(UUID publicId);

    @Query("SELECT s FROM StoredObject s WHERE s.folder.id = :folderId ORDER BY s.createdAt DESC")
    List<StoredObject> findByFolderId(Integer folderId);

    /** Listings show finished uploads only — a pending row has no bytes behind it yet. */
    @Query("SELECT s FROM StoredObject s WHERE s.folder.id = :folderId AND s.status = 'ready' "
        + "ORDER BY s.createdAt DESC")
    List<StoredObject> findReadyByFolderId(Integer folderId);

    @Query("SELECT s FROM StoredObject s WHERE s.objectKey LIKE :prefix% ORDER BY s.objectKey")
    List<StoredObject> findByKeyPrefix(String prefix);

    @Query("SELECT COUNT(s) FROM StoredObject s WHERE s.folder.id = :folderId")
    long countByFolderId(Integer folderId);
}
