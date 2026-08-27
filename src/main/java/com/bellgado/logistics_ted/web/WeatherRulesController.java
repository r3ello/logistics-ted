package com.bellgado.logistics_ted.web;

import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/weather/rules")
public class WeatherRulesController {

    private final JdbcTemplate jdbc;

    public WeatherRulesController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping
    public ResponseEntity<?> list() {
        List<Map<String, Object>> rules = jdbc.queryForList("""
            SELECT r.stage_order, st.stage_name, st.stage_name_en,
                   r.max_precipitation_mm, r.min_temp_c, r.max_temp_c,
                   r.max_wind_kph, r.requires_dry_days_before, r.notes_en, r.notes_bg
            FROM stage_weather_rule r
            JOIN stage_type st ON st.stage_order = r.stage_order
            ORDER BY r.stage_order
            """);
        return ResponseEntity.ok(rules);
    }

    @PutMapping("/{stageOrder}")
    public ResponseEntity<?> update(@PathVariable int stageOrder,
                                    @RequestBody Map<String, Object> body) {
        int updated = jdbc.update("""
            UPDATE stage_weather_rule
               SET max_precipitation_mm    = ?,
                   min_temp_c              = ?,
                   max_temp_c              = ?,
                   max_wind_kph            = ?,
                   requires_dry_days_before= ?
             WHERE stage_order = ?
            """,
            toDouble(body.get("maxPrecipMm")),
            toDouble(body.get("minTempC")),
            toDouble(body.get("maxTempC")),
            toDouble(body.get("maxWindKph")),
            toInt(body.get("dryDaysBefore")),
            stageOrder);
        if (updated == 0) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private static Double toDouble(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).doubleValue();
        String s = o.toString().trim();
        return s.isEmpty() ? null : Double.parseDouble(s);
    }

    private static int toInt(Object o) {
        if (o == null) return 0;
        if (o instanceof Number) return ((Number) o).intValue();
        String s = o.toString().trim();
        return s.isEmpty() ? 0 : Integer.parseInt(s);
    }
}
