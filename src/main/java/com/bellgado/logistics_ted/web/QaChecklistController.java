package com.bellgado.logistics_ted.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/qa")
public class QaChecklistController {

    private final JdbcTemplate jdbc;

    public QaChecklistController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** All checklist items grouped by stage. */
    @GetMapping("/checklist-items")
    public ResponseEntity<?> getAll() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT qi.id, qi.stage_order, st.stage_name, st.stage_name_en,
                   qi.item_text_bg, qi.item_text_en, qi.sort_order, qi.active
            FROM qa_checklist_item qi
            JOIN stage_type st ON st.stage_order = qi.stage_order
            ORDER BY qi.stage_order, qi.sort_order, qi.id
            """);
        return ResponseEntity.ok(rows);
    }

    /** Items for a specific stage. */
    @GetMapping("/checklist-items/stage/{stageOrder}")
    public ResponseEntity<?> getByStage(@PathVariable Integer stageOrder) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT id, stage_order, item_text_bg, item_text_en, sort_order, active
            FROM qa_checklist_item
            WHERE stage_order = ?
            ORDER BY sort_order, id
            """, stageOrder);
        return ResponseEntity.ok(rows);
    }

    /** Create a new checklist item. */
    @PostMapping("/checklist-items")
    @Transactional
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        Integer stageOrder = (Integer) body.get("stageOrder");
        String textBg = (String) body.get("itemTextBg");
        String textEn = (String) body.get("itemTextEn");
        if (stageOrder == null || textBg == null || textBg.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "stageOrder and itemTextBg required"));
        if (textEn == null || textEn.isBlank()) textEn = textBg;

        Integer maxOrder = jdbc.queryForObject(
            "SELECT COALESCE(MAX(sort_order), 0) FROM qa_checklist_item WHERE stage_order = ?",
            Integer.class, stageOrder);

        Map<String, Object> result = jdbc.queryForMap("""
            INSERT INTO qa_checklist_item (stage_order, item_text_bg, item_text_en, sort_order)
            VALUES (?, ?, ?, ?)
            RETURNING id, stage_order, item_text_bg, item_text_en, sort_order, active
            """, stageOrder, textBg, textEn, (maxOrder == null ? 0 : maxOrder) + 1);
        return ResponseEntity.ok(result);
    }

    /** Update a checklist item. */
    @PutMapping("/checklist-items/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable Integer id, @RequestBody Map<String, Object> body) {
        String textBg = (String) body.get("itemTextBg");
        String textEn = (String) body.get("itemTextEn");
        Boolean active = body.get("active") != null ? (Boolean) body.get("active") : null;
        Integer sortOrder = body.get("sortOrder") != null ? (Integer) body.get("sortOrder") : null;

        if (textBg != null)
            jdbc.update("UPDATE qa_checklist_item SET item_text_bg = ? WHERE id = ?", textBg, id);
        if (textEn != null)
            jdbc.update("UPDATE qa_checklist_item SET item_text_en = ? WHERE id = ?", textEn, id);
        if (active != null)
            jdbc.update("UPDATE qa_checklist_item SET active = ? WHERE id = ?", active, id);
        if (sortOrder != null)
            jdbc.update("UPDATE qa_checklist_item SET sort_order = ? WHERE id = ?", sortOrder, id);

        Map<String, Object> result = jdbc.queryForMap(
            "SELECT id, stage_order, item_text_bg, item_text_en, sort_order, active FROM qa_checklist_item WHERE id = ?", id);
        return ResponseEntity.ok(result);
    }

    /** Delete a checklist item. */
    @DeleteMapping("/checklist-items/{id}")
    @Transactional
    public ResponseEntity<?> delete(@PathVariable Integer id) {
        int rows = jdbc.update("DELETE FROM qa_checklist_item WHERE id = ?", id);
        if (rows == 0) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of("ok", true));
    }
}
