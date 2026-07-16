package com.bellgado.logistics_ted.repository;

import com.bellgado.logistics_ted.domain.DocFolderTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface DocFolderTemplateRepository extends JpaRepository<DocFolderTemplate, Integer> {

    @Query("SELECT t FROM DocFolderTemplate t ORDER BY t.sortOrder, t.id")
    List<DocFolderTemplate> findAllOrdered();
}
