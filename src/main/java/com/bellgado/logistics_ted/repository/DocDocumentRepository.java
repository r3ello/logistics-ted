package com.bellgado.logistics_ted.repository;

import com.bellgado.logistics_ted.domain.DocDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface DocDocumentRepository extends JpaRepository<DocDocument, Integer> {

    List<DocDocument> findByFolderIdOrderBySortOrder(Integer folderId);
}
