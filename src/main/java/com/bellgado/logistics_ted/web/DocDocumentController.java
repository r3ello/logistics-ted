package com.bellgado.logistics_ted.web;

import com.bellgado.logistics_ted.domain.DocDocument;
import com.bellgado.logistics_ted.domain.DocFolder;
import com.bellgado.logistics_ted.repository.DocDocumentRepository;
import com.bellgado.logistics_ted.repository.DocFolderRepository;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/doc-documents")
public class DocDocumentController {

    private final DocDocumentRepository documents;
    private final DocFolderRepository   folders;

    public DocDocumentController(DocDocumentRepository documents, DocFolderRepository folders) {
        this.documents = documents;
        this.folders   = folders;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<Map<String, Object>> byFolder(@RequestParam Integer folderId) {
        return documents.findByFolderIdOrderBySortOrder(folderId)
            .stream().map(this::toDto).toList();
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        Integer folderId = Integer.parseInt(body.get("folderId").toString());
        DocFolder folder = folders.findById(folderId).orElse(null);
        if (folder == null) return ResponseEntity.badRequest().body(Map.of("error", "Folder not found"));
        String titleEn = body.getOrDefault("titleEn", "").toString().trim();
        if (titleEn.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "Title is required"));

        DocDocument doc = new DocDocument();
        doc.setFolder(folder);
        doc.setTitleEn(titleEn);
        doc.setTitleBg(body.getOrDefault("titleBg", "").toString());
        doc.setLinkUrl(nullOrString(body.get("linkUrl")));
        doc.setDocType(body.getOrDefault("docType", "PDF").toString());
        doc.setDescription(nullOrString(body.get("description")));
        doc.setSortOrder(body.get("sortOrder") != null ? Integer.parseInt(body.get("sortOrder").toString()) : 0);
        return ResponseEntity.ok(toDto(documents.save(doc)));
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable Integer id, @RequestBody Map<String, Object> body) {
        return documents.findById(id).map(doc -> {
            if (body.containsKey("titleEn") && !body.get("titleEn").toString().isBlank())
                doc.setTitleEn(body.get("titleEn").toString());
            if (body.containsKey("titleBg"))     doc.setTitleBg(body.get("titleBg").toString());
            if (body.containsKey("linkUrl"))      doc.setLinkUrl(nullOrString(body.get("linkUrl")));
            if (body.containsKey("docType"))      doc.setDocType(body.get("docType").toString());
            if (body.containsKey("description"))  doc.setDescription(nullOrString(body.get("description")));
            return ResponseEntity.ok((Object) toDto(documents.save(doc)));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> delete(@PathVariable Integer id) {
        if (!documents.existsById(id)) return ResponseEntity.notFound().build();
        documents.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // ── helpers ──────────────────────────────────────────────────────────────
    private Map<String, Object> toDto(DocDocument d) {
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
