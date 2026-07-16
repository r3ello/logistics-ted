package com.bellgado.logistics_ted.repository;

import com.bellgado.logistics_ted.domain.DocFolder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface DocFolderRepository extends JpaRepository<DocFolder, Integer> {

    @Query("SELECT f FROM DocFolder f WHERE f.parent IS NULL ORDER BY f.sortOrder")
    List<DocFolder> findTopLevel();

    @Query("SELECT f FROM DocFolder f WHERE f.parent.id = :parentId ORDER BY f.sortOrder")
    List<DocFolder> findByParentId(Integer parentId);

    @Query("SELECT f FROM DocFolder f LEFT JOIN FETCH f.parent ORDER BY f.sortOrder, f.id")
    List<DocFolder> findAllOrdered();

    @Query("SELECT f FROM DocFolder f WHERE f.code = :code AND f.parent IS NULL")
    java.util.Optional<DocFolder> findTopLevelByCode(String code);

    @Query("SELECT f FROM DocFolder f WHERE f.folderType = :folderType")
    java.util.Optional<DocFolder> findByFolderType(String folderType);

    @Query(value = "SELECT MAX(CAST(code AS INTEGER)) FROM doc_folder WHERE parent_id IS NULL AND code ~ '^[0-9]+$'", nativeQuery = true)
    Integer findMaxTopLevelCode();

    @Query("SELECT f FROM DocFolder f WHERE f.code = :code AND f.parent.id = :parentId")
    java.util.Optional<DocFolder> findByCodeAndParentId(String code, Integer parentId);

    @org.springframework.data.jpa.repository.Modifying
    @Query("DELETE FROM DocFolder f WHERE f.code = :code")
    void deleteByCode(String code);
}
