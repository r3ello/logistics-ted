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

    /** Returns full recursive tree assembled in memory from a single flat query. */
    @GetMapping
    @Transactional(readOnly = true)
    public List<Map<String, Object>> tree() {
        List<DocFolder> all = folders.findAllOrdered();
        Map<Integer, Map<String, Object>> byId = new LinkedHashMap<>();
        for (DocFolder f : all) byId.put(f.getId(), toDto(f));

        // load documents per folder
        Map<Integer, List<Map<String, Object>>> docsByFolder = new LinkedHashMap<>();
        for (DocDocument d : documents.findAllWithFolder()) {
            docsByFolder.computeIfAbsent(d.getFolder().getId(), k -> new ArrayList<>()).add(docDto(d));
        }
        for (Map<String, Object> node : byId.values()) {
            int id = (Integer) node.get("id");
            node.put("documents", docsByFolder.getOrDefault(id, List.of()));
            node.put("children", new ArrayList<>());
        }

        List<Map<String, Object>> roots = new ArrayList<>();
        for (DocFolder f : all) {
            Map<String, Object> node = byId.get(f.getId());
            if (f.getParent() == null) {
                roots.add(node);
            } else {
                Map<String, Object> parent = byId.get(f.getParent().getId());
                if (parent != null) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> children = (List<Map<String, Object>>) parent.get("children");
                    children.add(node);
                }
            }
        }
        roots.sort((a, b) -> {
            String ca = (String) a.get("code"), cb = (String) b.get("code");
            try { return Integer.compare(Integer.parseInt(ca), Integer.parseInt(cb)); } catch (NumberFormatException e) { return ca.compareTo(cb); }
        });
        return roots;
    }

    /** Returns flat list of all folders — used by frontend to build parent dropdown. */
    @GetMapping("/flat")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> flat() {
        return folders.findAllOrdered().stream().map(this::toDto).toList();
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        String labelEn = body.containsKey("labelEn") ? body.get("labelEn").toString().trim() : "";
        String labelBg = body.containsKey("labelBg") ? body.get("labelBg").toString().trim() : "";
        if (labelEn.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "labelEn is required"));
        if (labelBg.isEmpty()) labelBg = labelEn;
        boolean isTopLevel = body.get("parentId") == null;
        DocFolder f = new DocFolder();
        f.setLabelEn(labelEn);
        f.setLabelBg(labelBg);
        if (isTopLevel) {
            Integer max = folders.findMaxTopLevelCode();
            int next = (max == null ? -1 : max) + 1;
            f.setCode(String.format("%02d", next));
        } else {
            f.setCode(labelEn.toUpperCase().replaceAll("[^A-Z0-9]", "_"));
        }
        f.setIcon(body.containsKey("icon") ? body.get("icon").toString() : "");
        f.setColor(body.containsKey("color") ? body.get("color").toString() : "#4f8ef7");
        f.setSortOrder(body.containsKey("sortOrder") ? Integer.parseInt(body.get("sortOrder").toString()) : 0);
        if (body.containsKey("linkUrl")) f.setLinkUrl(nullOrString(body.get("linkUrl")));
        if (!isTopLevel) {
            folders.findById(Integer.parseInt(body.get("parentId").toString())).ifPresent(f::setParent);
        }
        return ResponseEntity.ok(toDto(folders.save(f)));
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable Integer id, @RequestBody Map<String, Object> body) {
        return folders.findById(id).map(f -> {
            if (body.containsKey("labelEn") && body.get("labelEn") != null) f.setLabelEn(body.get("labelEn").toString().trim());
            if (body.containsKey("labelBg") && body.get("labelBg") != null) f.setLabelBg(body.get("labelBg").toString().trim());
            if (body.containsKey("linkUrl")) f.setLinkUrl(nullOrString(body.get("linkUrl")));
            if (body.containsKey("icon"))    f.setIcon(body.get("icon") == null ? "" : body.get("icon").toString());
            if (body.containsKey("color"))   f.setColor(body.get("color") == null ? "#4f8ef7" : body.get("color").toString());
            if (body.containsKey("sortOrder") && body.get("sortOrder") != null)
                f.setSortOrder(Integer.parseInt(body.get("sortOrder").toString()));
            if (body.containsKey("parentId")) {
                if (body.get("parentId") == null) {
                    f.setParent(null);
                } else {
                    int pid = Integer.parseInt(body.get("parentId").toString());
                    if (pid != id) folders.findById(pid).ifPresent(f::setParent);
                }
            }
            return ResponseEntity.ok((Object) toDto(folders.save(f)));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> delete(@PathVariable Integer id) {
        if (!folders.existsById(id)) return ResponseEntity.notFound().build();
        deleteCascade(id);
        return ResponseEntity.noContent().build();
    }

    private void deleteCascade(Integer id) {
        for (DocFolder child : folders.findByParentId(id)) deleteCascade(child.getId());
        documents.deleteAll(documents.findByFolderIdOrderBySortOrder(id));
        folders.deleteById(id);
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
