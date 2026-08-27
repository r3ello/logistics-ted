package com.bellgado.logistics_ted.web;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import com.fasterxml.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/weather")
public class WeatherRecommendationController {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10)).build();

    public WeatherRecommendationController(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc   = jdbc;
        this.mapper = mapper;
    }

    @GetMapping("/recommendations/{houseId}")
    public ResponseEntity<?> recommendations(@PathVariable Integer houseId) throws Exception {
        // 1. Load house coordinates
        List<Map<String, Object>> houses = jdbc.queryForList(
            "SELECT lat, lng, name, address FROM house WHERE id = ?", houseId);
        if (houses.isEmpty()) return ResponseEntity.notFound().build();
        Map<String, Object> house = houses.get(0);
        Object latObj = house.get("lat"), lngObj = house.get("lng");
        if (latObj == null || lngObj == null)
            return ResponseEntity.badRequest().body(Map.of("error", "House has no coordinates"));
        double lat = ((Number) latObj).doubleValue();
        double lng = ((Number) lngObj).doubleValue();

        // 2. Load stage weather rules — skip stages already DONE for this house
        List<Map<String, Object>> rules = jdbc.queryForList("""
            SELECT r.stage_order, st.stage_name, st.stage_name_en,
                   r.max_precipitation_mm, r.min_temp_c, r.max_temp_c,
                   r.max_wind_kph, r.requires_dry_days_before, r.notes_en, r.notes_bg
            FROM stage_weather_rule r
            JOIN stage_type st ON st.stage_order = r.stage_order
            WHERE NOT EXISTS (
                SELECT 1 FROM house_stage hs
                WHERE hs.house_id = ? AND hs.stage_order = r.stage_order AND hs.status = 'DONE'
            )
            ORDER BY r.stage_order
            """, houseId);

        // 3. Fetch 16-day forecast from Open-Meteo — retry up to 3 times, 2s apart
        String url = String.format(
            "https://api.open-meteo.com/v1/forecast?latitude=%.6f&longitude=%.6f" +
            "&daily=precipitation_sum,temperature_2m_max,temperature_2m_min,windspeed_10m_max,weathercode" +
            "&forecast_days=16&timezone=Europe%%2FSofia",
            lat, lng);

        HttpResponse<String> resp = null;
        int attempts = 0;
        while (attempts < 3) {
            attempts++;
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15)).GET().build();
                resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) break;
            } catch (Exception ignored) {}
            if (attempts < 3) Thread.sleep(2000);
        }
        if (resp == null || resp.statusCode() != 200)
            return ResponseEntity.status(503).body(Map.of("error", "weather_unavailable"));

        @SuppressWarnings("unchecked")
        Map<String, Object> forecast = mapper.readValue(resp.body(), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> daily = (Map<String, Object>) forecast.get("daily");

        List<String>  dates  = castList(daily.get("time"));
        List<Number>  precip = castList(daily.get("precipitation_sum"));
        List<Number>  tmax   = castList(daily.get("temperature_2m_max"));
        List<Number>  tmin   = castList(daily.get("temperature_2m_min"));
        List<Number>  wind   = castList(daily.get("windspeed_10m_max"));
        List<Number>  wcode  = castList(daily.get("weathercode"));

        // 4. Build day-level weather summary
        List<Map<String, Object>> days = new ArrayList<>();
        for (int i = 0; i < dates.size(); i++) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("date",   dates.get(i));
            d.put("precip", precip.get(i) != null ? precip.get(i).doubleValue() : 0.0);
            d.put("tmax",   tmax.get(i)   != null ? tmax.get(i).doubleValue()   : 20.0);
            d.put("tmin",   tmin.get(i)   != null ? tmin.get(i).doubleValue()   : 10.0);
            d.put("wind",   wind.get(i)   != null ? wind.get(i).doubleValue()   : 0.0);
            d.put("wcode",  wcode.get(i)  != null ? wcode.get(i).intValue()     : 0);
            days.add(d);
        }

        // 5. Evaluate each rule against each day
        List<Map<String, Object>> stageResults = new ArrayList<>();
        for (Map<String, Object> rule : rules) {
            Double maxPrecip = toDouble(rule.get("max_precipitation_mm"));
            Double minTemp   = toDouble(rule.get("min_temp_c"));
            Double maxTemp   = toDouble(rule.get("max_temp_c"));
            Double maxWind   = toDouble(rule.get("max_wind_kph"));
            int    dryBefore = rule.get("requires_dry_days_before") != null
                               ? ((Number) rule.get("requires_dry_days_before")).intValue() : 0;

            List<Map<String, Object>> dayRatings = new ArrayList<>();
            for (int i = 0; i < days.size(); i++) {
                Map<String, Object> day  = days.get(i);
                double dp  = (double) day.get("precip");
                double dtx = (double) day.get("tmax");
                double dtn = (double) day.get("tmin");
                double dw  = (double) day.get("wind");

                List<Map<String, Object>> reasons = new ArrayList<>();
                if (maxPrecip != null && dp > maxPrecip)
                    reasons.add(reason("rain",     dp,  maxPrecip));
                if (minTemp  != null && dtn < minTemp)
                    reasons.add(reason("min_temp", dtn, minTemp));
                if (maxTemp  != null && dtx > maxTemp)
                    reasons.add(reason("max_temp", dtx, maxTemp));
                if (maxWind  != null && dw > maxWind)
                    reasons.add(reason("wind",     dw,  maxWind));

                // check dry-days-before
                boolean dryOk = true;
                if (dryBefore > 0) {
                    for (int b = Math.max(0, i - dryBefore); b < i; b++) {
                        if ((double) days.get(b).get("precip") > 2.0) { dryOk = false; break; }
                    }
                    if (!dryOk) reasons.add(reason("dry_days", dryBefore, null));
                }

                String rating;
                if (reasons.isEmpty())       rating = "GOOD";
                else if (reasons.size() == 1 && reasons.stream().noneMatch(r -> "rain".equals(r.get("type"))))
                    rating = "MARGINAL";
                else if (reasons.size() == 1) rating = "MARGINAL";
                else                          rating = "BAD";

                Map<String, Object> dr = new LinkedHashMap<>();
                dr.put("date",    day.get("date"));
                dr.put("rating",  rating);
                dr.put("reasons", reasons);
                dr.put("precip",  dp);
                dr.put("tmax",    dtx);
                dr.put("tmin",    dtn);
                dr.put("wind",    dw);
                dr.put("wcode",   day.get("wcode"));
                dayRatings.add(dr);
            }

            // best window: longest consecutive GOOD streak
            int bestLen = 0, bestStart = -1, cur = 0, curStart = 0;
            for (int i = 0; i < dayRatings.size(); i++) {
                if ("GOOD".equals(dayRatings.get(i).get("rating"))) {
                    if (cur == 0) curStart = i;
                    cur++;
                    if (cur > bestLen) { bestLen = cur; bestStart = curStart; }
                } else { cur = 0; }
            }

            Map<String, Object> sr = new LinkedHashMap<>();
            sr.put("stageOrder",   rule.get("stage_order"));
            sr.put("stageName",    rule.get("stage_name"));
            sr.put("stageNameEn",  rule.get("stage_name_en"));
            sr.put("notesEn",      rule.get("notes_en"));
            sr.put("notesBg",      rule.get("notes_bg"));
            sr.put("maxPrecipMm",  rule.get("max_precipitation_mm"));
            sr.put("minTempC",     rule.get("min_temp_c"));
            sr.put("maxTempC",     rule.get("max_temp_c"));
            sr.put("maxWindKph",   rule.get("max_wind_kph"));
            sr.put("dryDaysBefore",rule.get("requires_dry_days_before"));
            sr.put("days",         dayRatings);
            sr.put("bestWindowStart", bestStart >= 0 ? dayRatings.get(bestStart).get("date") : null);
            sr.put("bestWindowDays",  bestLen);
            stageResults.add(sr);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("houseName", house.get("name"));
        result.put("address",   house.get("address"));
        result.put("lat", lat); result.put("lng", lng);
        result.put("forecastDays", days);
        result.put("stages", stageResults);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/stage/{stageOrder}")
    public ResponseEntity<?> stageAllHouses(@PathVariable int stageOrder) throws Exception {
        // Load the one rule
        List<Map<String, Object>> ruleRows = jdbc.queryForList("""
            SELECT r.stage_order, st.stage_name, st.stage_name_en,
                   r.max_precipitation_mm, r.min_temp_c, r.max_temp_c,
                   r.max_wind_kph, r.requires_dry_days_before
            FROM stage_weather_rule r
            JOIN stage_type st ON st.stage_order = r.stage_order
            WHERE r.stage_order = ?
            """, stageOrder);
        if (ruleRows.isEmpty()) return ResponseEntity.notFound().build();
        Map<String, Object> rule = ruleRows.get(0);

        List<Map<String, Object>> houses = jdbc.queryForList(
            "SELECT id, name FROM house WHERE lat IS NOT NULL AND lng IS NOT NULL ORDER BY name");
        if (houses.isEmpty()) return ResponseEntity.ok(List.of());

        // Which houses have this stage DONE?
        Set<Integer> doneHouses = new HashSet<>();
        jdbc.queryForList(
            "SELECT house_id FROM house_stage WHERE stage_order = ? AND status = 'DONE'", stageOrder)
            .forEach(r -> doneHouses.add(((Number) r.get("house_id")).intValue()));

        List<Map<String, Object>> singleRuleList = List.of(rule);
        List<CompletableFuture<Map<String, Object>>> futures = new ArrayList<>();
        for (Map<String, Object> house : houses) {
            int houseId = ((Number) house.get("id")).intValue();
            boolean done = doneHouses.contains(houseId);
            futures.add(CompletableFuture.supplyAsync(() -> {
                if (done) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("houseId",   houseId);
                    r.put("houseName", house.get("name"));
                    r.put("done",      true);
                    r.put("days",      List.of());
                    return r;
                }
                try { return fetchHouseSummary(houseId, house, singleRuleList); }
                catch (Exception e) { return null; }
            }));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        List<Map<String, Object>> results = new ArrayList<>();
        for (CompletableFuture<Map<String, Object>> f : futures) {
            Map<String, Object> r = f.get();
            if (r != null) results.add(r);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("stageName",   rule.get("stage_name"));
        resp.put("stageNameEn", rule.get("stage_name_en"));
        resp.put("houses",      results);
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/all-houses")
    public ResponseEntity<?> allHouses() throws Exception {
        // Load all houses with coordinates
        List<Map<String, Object>> houses = jdbc.queryForList(
            "SELECT id, name FROM house WHERE lat IS NOT NULL AND lng IS NOT NULL ORDER BY name");
        if (houses.isEmpty()) return ResponseEntity.ok(List.of());

        // Load all stage weather rules once
        List<Map<String, Object>> allRules = jdbc.queryForList("""
            SELECT r.stage_order, st.stage_name, st.stage_name_en,
                   r.max_precipitation_mm, r.min_temp_c, r.max_temp_c,
                   r.max_wind_kph, r.requires_dry_days_before
            FROM stage_weather_rule r
            JOIN stage_type st ON st.stage_order = r.stage_order
            ORDER BY r.stage_order
            """);

        // Fetch forecasts in parallel
        List<CompletableFuture<Map<String, Object>>> futures = new ArrayList<>();
        for (Map<String, Object> house : houses) {
            int houseId = ((Number) house.get("id")).intValue();
            futures.add(CompletableFuture.supplyAsync(() -> {
                try { return fetchHouseSummary(houseId, house, allRules); }
                catch (Exception e) { return null; }
            }));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        List<Map<String, Object>> results = new ArrayList<>();
        for (CompletableFuture<Map<String, Object>> f : futures) {
            Map<String, Object> r = f.get();
            if (r != null) results.add(r);
        }
        return ResponseEntity.ok(results);
    }

    private Map<String, Object> fetchHouseSummary(int houseId, Map<String, Object> house,
                                                   List<Map<String, Object>> allRules) throws Exception {
        Map<String, Object> coords = jdbc.queryForMap("SELECT lat, lng FROM house WHERE id = ?", houseId);
        double lat = ((Number) coords.get("lat")).doubleValue();
        double lng = ((Number) coords.get("lng")).doubleValue();

        // Rules not yet DONE for this house
        Set<Integer> doneStages = new HashSet<>();
        jdbc.queryForList(
            "SELECT stage_order FROM house_stage WHERE house_id = ? AND status = 'DONE'", houseId)
            .forEach(r -> doneStages.add(((Number) r.get("stage_order")).intValue()));

        List<Map<String, Object>> rules = new ArrayList<>();
        for (Map<String, Object> r : allRules) {
            int so = ((Number) r.get("stage_order")).intValue();
            if (!doneStages.contains(so)) rules.add(r);
        }

        // Fetch forecast
        String url = String.format(
            "https://api.open-meteo.com/v1/forecast?latitude=%.6f&longitude=%.6f" +
            "&daily=precipitation_sum,temperature_2m_max,temperature_2m_min,windspeed_10m_max,weathercode" +
            "&forecast_days=16&timezone=Europe%%2FSofia", lat, lng);

        HttpResponse<String> resp = null;
        for (int a = 0; a < 3; a++) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15)).GET().build();
                resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) break;
            } catch (Exception ignored) {}
            if (a < 2) Thread.sleep(2000);
        }
        if (resp == null || resp.statusCode() != 200) return null;

        @SuppressWarnings("unchecked")
        Map<String, Object> forecast = mapper.readValue(resp.body(), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> daily = (Map<String, Object>) forecast.get("daily");

        List<String> dates  = castList(daily.get("time"));
        List<Number> precip = castList(daily.get("precipitation_sum"));
        List<Number> tmax   = castList(daily.get("temperature_2m_max"));
        List<Number> tmin   = castList(daily.get("temperature_2m_min"));
        List<Number> wind   = castList(daily.get("windspeed_10m_max"));
        List<Number> wcode  = castList(daily.get("weathercode"));

        List<Map<String, Object>> days = new ArrayList<>();
        int limit5 = dates.size();
        for (int i = 0; i < limit5; i++) {
            double dp  = precip.get(i) != null ? precip.get(i).doubleValue() : 0.0;
            double dtx = tmax.get(i)   != null ? tmax.get(i).doubleValue()   : 20.0;
            double dtn = tmin.get(i)   != null ? tmin.get(i).doubleValue()   : 10.0;
            double dw  = wind.get(i)   != null ? wind.get(i).doubleValue()   : 0.0;
            int    wc  = wcode.get(i)  != null ? wcode.get(i).intValue()     : 0;

            List<Map<String, Object>> blocked  = new ArrayList<>();
            List<Map<String, Object>> marginal = new ArrayList<>();

            for (Map<String, Object> rule : rules) {
                Double maxPrecip = toDouble(rule.get("max_precipitation_mm"));
                Double minTemp   = toDouble(rule.get("min_temp_c"));
                Double maxTemp   = toDouble(rule.get("max_temp_c"));
                Double maxWind   = toDouble(rule.get("max_wind_kph"));
                int dryBefore    = rule.get("requires_dry_days_before") != null
                                   ? ((Number) rule.get("requires_dry_days_before")).intValue() : 0;

                List<Map<String, Object>> reasons = new ArrayList<>();
                if (maxPrecip != null && dp  > maxPrecip) reasons.add(reason("rain",     dp,  maxPrecip));
                if (minTemp  != null && dtn < minTemp)    reasons.add(reason("min_temp", dtn, minTemp));
                if (maxTemp  != null && dtx > maxTemp)    reasons.add(reason("max_temp", dtx, maxTemp));
                if (maxWind  != null && dw  > maxWind)    reasons.add(reason("wind",     dw,  maxWind));
                // dry days before — simplified: check prior day only in this context
                if (dryBefore > 0 && i > 0 && precip.get(i - 1) != null
                        && precip.get(i - 1).doubleValue() > 2.0)
                    reasons.add(reason("dry_days", dryBefore, null));

                if (reasons.isEmpty()) continue;

                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("stageOrder",  rule.get("stage_order"));
                entry.put("stageName",   rule.get("stage_name"));
                entry.put("stageNameEn", rule.get("stage_name_en"));
                entry.put("reasons",     reasons);

                boolean isBad = reasons.stream().anyMatch(r -> List.of("rain","min_temp","max_temp").contains(r.get("type")));
                if (isBad) blocked.add(entry); else marginal.add(entry);
            }

            String status = !blocked.isEmpty() ? "BAD" : !marginal.isEmpty() ? "MARGINAL" : "GOOD";
            Map<String, Object> day = new LinkedHashMap<>();
            day.put("date",     dates.get(i));
            day.put("wcode",    wc);
            day.put("precip",   dp);
            day.put("tmax",     dtx);
            day.put("wind",     dw);
            day.put("status",   status);
            day.put("blocked",  blocked);
            day.put("marginal", marginal);
            days.add(day);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("houseId",   houseId);
        result.put("houseName", house.get("name"));
        result.put("days",      days);
        return result;
    }

    private static Map<String, Object> reason(String type, double actual, Double limit) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("type",   type);
        r.put("actual", actual);
        r.put("limit",  limit);
        return r;
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> castList(Object o) {
        return o instanceof List ? (List<T>) o : List.of();
    }

    private static Double toDouble(Object o) {
        return o instanceof Number ? ((Number) o).doubleValue() : null;
    }
}
