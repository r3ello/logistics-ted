package com.bellgado.logistics_ted.web;

import com.bellgado.logistics_ted.domain.DocFolder;
import com.bellgado.logistics_ted.domain.DocDocument;
import com.bellgado.logistics_ted.repository.DocFolderRepository;
import com.bellgado.logistics_ted.repository.DocDocumentRepository;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/doc-folders")
public class DocFolderController {

    private final DocFolderRepository folders;
    private final DocDocumentRepository documents;

    public DocFolderController(DocFolderRepository folders, DocDocumentRepository documents) {
        this.folders   = folders;
        this.documents = documents;
    }

    /** Returns full tree: top-level departments with their subfolders and documents. */
    @GetMapping
    @Transactional(readOnly = true)
    public List<Map<String, Object>> tree() {
        return folders.findTopLevel().stream().map(dept -> {
            Map<String, Object> node = toDto(dept);
            List<Map<String, Object>> children = folders.findByParentId(dept.getId()).stream()
                .map(sub -> {
                    Map<String, Object> snode = toDto(sub);
                    snode.put("documents", documents.findByFolderIdOrderBySortOrder(sub.getId())
                        .stream().map(this::docDto).toList());
                    return snode;
                }).toList();
            node.put("children", children);
            node.put("documents", documents.findByFolderIdOrderBySortOrder(dept.getId())
                .stream().map(this::docDto).toList());
            return node;
        }).toList();
    }

    /** Update a folder's link_url (and optionally labels). */
    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable Integer id, @RequestBody Map<String, Object> body) {
        return folders.findById(id).map(f -> {
            if (body.containsKey("linkUrl")) f.setLinkUrl(nullOrString(body.get("linkUrl")));
            if (body.containsKey("labelEn")) f.setLabelEn(body.get("labelEn").toString());
            if (body.containsKey("labelBg")) f.setLabelBg(body.get("labelBg").toString());
            return ResponseEntity.ok((Object) toDto(folders.save(f)));
        }).orElse(ResponseEntity.notFound().build());
    }

    // ── helpers ──────────────────────────────────────────────────────────────
    private Map<String, Object> toDto(DocFolder f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",        f.getId());
        m.put("code",      f.getCode());
        m.put("labelEn",   f.getLabelEn());
        m.put("labelBg",   f.getLabelBg());
        m.put("icon",      f.getIcon());
        m.put("color",     f.getColor());
        m.put("linkUrl",   f.getLinkUrl());
        m.put("sortOrder", f.getSortOrder());
        m.put("parentId",  f.getParent() != null ? f.getParent().getId() : null);
        return m;
    }

    private Map<String, Object> docDto(DocDocument d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",          d.getId());
        m.put("folderId",    d.getFolder().getId());
        m.put("titleEn",     d.getTitleEn());
        m.put("titleBg",     d.getTitleBg());
        m.put("linkUrl",     d.getLinkUrl());
        m.put("docType",     d.getDocType());
        m.put("description", d.getDescription());
        m.put("sortOrder",   d.getSortOrder());
        m.put("createdAt",   d.getCreatedAt() != null ? d.getCreatedAt().toString() : null);
        return m;
    }

    private String nullOrString(Object v) {
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }
}
