package com.bellgado.logistics_ted.web;

import com.bellgado.logistics_ted.domain.DocFolderTemplate;
import com.bellgado.logistics_ted.repository.DocFolderTemplateRepository;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/folder-templates")
@PreAuthorize("hasRole('ADMIN')")
public class DocFolderTemplateController {

    private final DocFolderTemplateRepository repo;

    public DocFolderTemplateController(DocFolderTemplateRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return repo.findAllOrdered().stream().map(this::toDto).toList();
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        String labelBg = str(body, "labelBg");
        String labelEn = str(body, "labelEn");
        if (labelBg.isEmpty() || labelEn.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "labelBg and labelEn are required"));

        DocFolderTemplate t = new DocFolderTemplate();
        t.setLabelBg(labelBg);
        t.setLabelEn(labelEn);
        t.setParentId(intOrNull(body, "parentId"));
        t.setSortOrder(intOr(body, "sortOrder", 0));
        t.setIcon(strOr(body, "icon", ""));
        t.setColor(strOr(body, "color", "#4f8ef7"));
        return ResponseEntity.ok(toDto(repo.save(t)));
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable Integer id, @RequestBody Map<String, Object> body) {
        return repo.findById(id).map(t -> {
            if (body.containsKey("labelBg"))   t.setLabelBg(str(body, "labelBg"));
            if (body.containsKey("labelEn"))   t.setLabelEn(str(body, "labelEn"));
            if (body.containsKey("parentId"))  t.setParentId(intOrNull(body, "parentId"));
            if (body.containsKey("sortOrder")) t.setSortOrder(intOr(body, "sortOrder", 0));
            if (body.containsKey("icon"))      t.setIcon(strOr(body, "icon", ""));
            if (body.containsKey("color"))     t.setColor(strOr(body, "color", "#4f8ef7"));
            return ResponseEntity.ok((Object) toDto(repo.save(t)));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> delete(@PathVariable Integer id) {
        if (!repo.existsById(id)) return ResponseEntity.notFound().build();
        // null out children's parentId first (orphan them rather than cascade-delete)
        repo.findAll().stream()
            .filter(t -> Objects.equals(t.getParentId(), id))
            .forEach(t -> { t.setParentId(null); repo.save(t); });
        repo.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private Map<String, Object> toDto(DocFolderTemplate t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",        t.getId());
        m.put("labelBg",   t.getLabelBg());
        m.put("labelEn",   t.getLabelEn());
        m.put("parentId",  t.getParentId());
        m.put("sortOrder", t.getSortOrder());
        m.put("icon",      t.getIcon());
        m.put("color",     t.getColor());
        return m;
    }

    private String str(Map<String, Object> b, String k) {
        Object v = b.get(k); return v == null ? "" : v.toString().trim();
    }
    private String strOr(Map<String, Object> b, String k, String def) {
        Object v = b.get(k); return (v == null || v.toString().isBlank()) ? def : v.toString().trim();
    }
    private Integer intOrNull(Map<String, Object> b, String k) {
        Object v = b.get(k); if (v == null) return null;
        try { return Integer.parseInt(v.toString()); } catch (NumberFormatException e) { return null; }
    }
    private int intOr(Map<String, Object> b, String k, int def) {
        Integer v = intOrNull(b, k); return v == null ? def : v;
    }
}
