package com.bellgado.logistics_ted.web;

import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/menu-config")
public class MenuConfigController {

    private final JdbcTemplate jdbc;

    public MenuConfigController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return jdbc.queryForList(
            "SELECT menu_key, section, label_en, label_bg, icon, visible, sort_order, is_group, parent_key " +
            "FROM menu_config ORDER BY section, sort_order");
    }

    @PutMapping("/{key}/visible")
    @Transactional
    public ResponseEntity<?> setVisible(@PathVariable String key, @RequestBody Map<String, Object> body) {
        if (!body.containsKey("visible")) return ResponseEntity.badRequest().body(Map.of("error", "visible required"));
        boolean visible = Boolean.parseBoolean(body.get("visible").toString());
        int updated = jdbc.update("UPDATE menu_config SET visible = ? WHERE menu_key = ?", visible, key);
        if (updated == 0) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** Reorder items — payload: [{menuKey, sortOrder, parentKey}] */
    @PutMapping("/reorder")
    @Transactional
    public ResponseEntity<?> reorder(@RequestBody List<Map<String, Object>> items) {
        for (int i = 0; i < items.size(); i++) {
            String key = items.get(i).get("menuKey").toString();
            Object parentKey = items.get(i).get("parentKey");
            int sortOrder = items.get(i).get("sortOrder") instanceof Number n
                ? n.intValue() : (i + 1);
            if (parentKey != null) {
                jdbc.update("UPDATE menu_config SET sort_order = ?, parent_key = ? WHERE menu_key = ?",
                    sortOrder, parentKey.toString(), key);
            } else {
                jdbc.update("UPDATE menu_config SET sort_order = ?, parent_key = NULL WHERE menu_key = ?",
                    sortOrder, key);
            }
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** Move a child item to a different parent group, inserting before a given sibling */
    @PutMapping("/reparent")
    @Transactional
    public ResponseEntity<?> reparent(@RequestBody List<Map<String, Object>> items) {
        for (Map<String, Object> item : items) {
            String key       = item.get("menuKey").toString();
            Object parentKey = item.get("parentKey");
            // compute new sort_order: max of siblings + 1
            Integer maxOrder;
            if (parentKey != null) {
                maxOrder = jdbc.queryForObject(
                    "SELECT COALESCE(MAX(sort_order),0) FROM menu_config WHERE parent_key = ?",
                    Integer.class, parentKey.toString());
                jdbc.update("UPDATE menu_config SET parent_key = ?, sort_order = ? WHERE menu_key = ?",
                    parentKey.toString(), (maxOrder == null ? 0 : maxOrder) + 1, key);
            } else {
                maxOrder = jdbc.queryForObject(
                    "SELECT COALESCE(MAX(sort_order),0) FROM menu_config WHERE parent_key IS NULL AND section = (SELECT section FROM menu_config WHERE menu_key = ?)",
                    Integer.class, key);
                jdbc.update("UPDATE menu_config SET parent_key = NULL, sort_order = ? WHERE menu_key = ?",
                    (maxOrder == null ? 0 : maxOrder) + 1, key);
            }
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }
}
