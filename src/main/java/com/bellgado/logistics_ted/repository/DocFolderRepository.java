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

    @Query("SELECT f FROM DocFolder f WHERE f.code = :code AND f.parent IS NULL")
    java.util.Optional<DocFolder> findTopLevelByCode(String code);

    @Query("SELECT f FROM DocFolder f WHERE f.code = :code AND f.parent.id = :parentId")
    java.util.Optional<DocFolder> findByCodeAndParentId(String code, Integer parentId);

    @org.springframework.data.jpa.repository.Modifying
    @Query("DELETE FROM DocFolder f WHERE f.code = :code")
    void deleteByCode(String code);
}
