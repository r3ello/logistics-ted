package com.bellgado.logistics_ted.repository;

import com.bellgado.logistics_ted.domain.DocDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface DocDocumentRepository extends JpaRepository<DocDocument, Integer> {

    List<DocDocument> findByFolderIdOrderBySortOrder(Integer folderId);

    @Query("SELECT d FROM DocDocument d JOIN FETCH d.folder ORDER BY d.folder.id, d.sortOrder")
    List<DocDocument> findAllWithFolder();
}
