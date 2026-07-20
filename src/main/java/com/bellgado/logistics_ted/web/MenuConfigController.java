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
            "SELECT menu_key, section, label_en, label_bg, icon, visible, sort_order " +
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

    @PutMapping("/reorder")
    @Transactional
    public ResponseEntity<?> reorder(@RequestBody List<Map<String, Object>> items) {
        for (int i = 0; i < items.size(); i++) {
            String key = items.get(i).get("menuKey").toString();
            jdbc.update("UPDATE menu_config SET sort_order = ? WHERE menu_key = ?", i + 1, key);
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }
}
