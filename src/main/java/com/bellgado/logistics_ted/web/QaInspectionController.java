package com.bellgado.logistics_ted.web;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import com.bellgado.logistics_ted.security.AppUserDetailsService.AuthenticatedUser;
import com.bellgado.logistics_ted.service.HouseTemplateFolderService;
import com.bellgado.logistics_ted.repository.DocFolderRepository;
import com.bellgado.logistics_ted.domain.DocFolder;

import java.sql.PreparedStatement;
import java.util.ArrayList;

@RestController
@RequestMapping("/api/qa")
public class QaInspectionController {

    private final JdbcTemplate jdbc;
    private final DocFolderRepository folderRepo;
    private final HouseTemplateFolderService folderService;

    public QaInspectionController(JdbcTemplate jdbc,
                                   DocFolderRepository folderRepo,
                                   HouseTemplateFolderService folderService) {
        this.jdbc = jdbc;
        this.folderRepo = folderRepo;
        this.folderService = folderService;
    }

    /**
     * Stages for a house that are COMPLETED and have checklist items defined,
     * grouped by whether they already have a QA inspection.
     */
    @GetMapping("/houses/{houseId}/pending-stages")
    public ResponseEntity<?> pendingStages(@PathVariable Integer houseId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT hs.stage_order,
                   st.stage_name,
                   st.stage_name_en,
                   qi.id AS inspection_id,
                   qi.result,
                   qi.notes,
                   qi.inspected_at
            FROM house_stage hs
            JOIN stage_type st ON st.stage_order = hs.stage_order
            LEFT JOIN qa_inspection qi ON qi.house_id = hs.house_id AND qi.stage_order = hs.stage_order
            WHERE hs.house_id = ?
              AND hs.status IN ('DONE', 'IN_PROGRESS')
              AND EXISTS (SELECT 1 FROM qa_checklist_item ci WHERE ci.stage_order = hs.stage_order AND ci.active = true)
            ORDER BY hs.stage_order
            """, houseId);
        return ResponseEntity.ok(rows);
    }

    /**
     * Checklist items for a stage, with existing inspection result if present.
     */
    @GetMapping("/houses/{houseId}/stages/{stageOrder}/checklist")
    public ResponseEntity<?> stageChecklist(@PathVariable Integer houseId,
                                             @PathVariable Integer stageOrder) {
        // Get or find existing inspection
        List<Map<String, Object>> inspections = jdbc.queryForList(
            "SELECT id, result, notes FROM qa_inspection WHERE house_id = ? AND stage_order = ?",
            houseId, stageOrder);

        Map<String, Object> inspection = inspections.isEmpty() ? null : inspections.get(0);

        List<Map<String, Object>> items = jdbc.queryForList("""
            SELECT ci.id, ci.item_text_bg, ci.item_text_en, ci.sort_order,
                   ii.passed, ii.note
            FROM qa_checklist_item ci
            LEFT JOIN qa_inspection_item ii ON ii.checklist_item_id = ci.id
              AND ii.inspection_id = ?
            WHERE ci.stage_order = ? AND ci.active = true
            ORDER BY ci.sort_order, ci.id
            """, inspection != null ? inspection.get("id") : -1, stageOrder);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("inspection", inspection);
        result.put("items", items);
        return ResponseEntity.ok(result);
    }

    /**
     * Submit a QA inspection for a house stage.
     * Body: { result: "PASS"|"FAIL", notes: "...", items: [{checklistItemId, passed, note}] }
     */
    @PostMapping("/houses/{houseId}/stages/{stageOrder}/inspect")
    @Transactional
    public ResponseEntity<?> submitInspection(@PathVariable Integer houseId,
                                               @PathVariable Integer stageOrder,
                                               @RequestBody Map<String, Object> body,
                                               @AuthenticationPrincipal AuthenticatedUser principal) {
        String result  = (String) body.get("result");
        String notes   = (String) body.get("notes");
        if (!"PASS".equals(result) && !"FAIL".equals(result))
            return ResponseEntity.badRequest().body(Map.of("error", "result must be PASS or FAIL"));

        // Upsert inspection row
        Integer existingId = null;
        List<Map<String, Object>> existing = jdbc.queryForList(
            "SELECT id FROM qa_inspection WHERE house_id = ? AND stage_order = ?", houseId, stageOrder);
        if (!existing.isEmpty()) {
            existingId = (Integer) existing.get(0).get("id");
            jdbc.update("UPDATE qa_inspection SET result=?, notes=?, inspector_id=?, inspected_at=NOW() WHERE id=?",
                result, notes, principal.getUserId(), existingId);
            jdbc.update("DELETE FROM qa_inspection_item WHERE inspection_id=?", existingId);
        } else {
            KeyHolder kh = new GeneratedKeyHolder();
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO qa_inspection (house_id, stage_order, inspector_id, result, notes) VALUES (?,?,?,?,?)",
                    new String[]{"id"});
                ps.setInt(1, houseId); ps.setInt(2, stageOrder);
                ps.setInt(3, principal.getUserId()); ps.setString(4, result); ps.setString(5, notes);
                return ps;
            }, kh);
            existingId = kh.getKey().intValue();
        }

        // Save item results
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");
        if (items != null) {
            for (Map<String, Object> item : items) {
                Integer ciId  = (Integer) item.get("checklistItemId");
                Boolean passed = (Boolean) item.get("passed");
                String note   = (String) item.get("note");
                if (ciId != null && passed != null)
                    jdbc.update("INSERT INTO qa_inspection_item (inspection_id, checklist_item_id, passed, note) VALUES (?,?,?,?)",
                        existingId, ciId, passed, note);
            }
        }

        return ResponseEntity.ok(Map.of("inspectionId", existingId, "result", result));
    }

    /**
     * Finds or creates the upload folder for a QA photo:
     * ACTIVE_SITES / house_{id} / Quality Control / {stageName}
     * Returns the folder id so the browser can request a presigned PUT directly.
     */
    @PostMapping("/houses/{houseId}/stage-folder")
    @Transactional
    public ResponseEntity<?> stageFolder(@PathVariable Integer houseId,
                                          @RequestBody Map<String, Object> body) {
        String stageName   = String.valueOf(body.getOrDefault("stageName", ""));
        String stageNameEn = String.valueOf(body.getOrDefault("stageNameEn", stageName));
        if (stageName.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "stageName is required"));

        // Find house root folder (under ACTIVE_SITES or COMPLETED_SITES)
        var activeSites = folderRepo.findByFolderType("ACTIVE_SITES");
        var completedSites = folderRepo.findByFolderType("COMPLETED_SITES");

        DocFolder houseFolder = null;
        if (activeSites.isPresent()) {
            houseFolder = folderRepo.findByCodeAndParentId("house_" + houseId, activeSites.get().getId()).orElse(null);
        }
        if (houseFolder == null && completedSites.isPresent()) {
            houseFolder = folderRepo.findByCodeAndParentId("house_" + houseId, completedSites.get().getId()).orElse(null);
        }
        if (houseFolder == null)
            return ResponseEntity.status(404).body(Map.of("error", "House folder not found"));

        // Find "Quality Control" subfolder
        DocFolder qaFolder = folderRepo.findByParentId(houseFolder.getId()).stream()
            .filter(f -> "Quality Control".equals(f.getLabelEn()) || "Контрол качество".equals(f.getLabelBg()))
            .findFirst().orElse(null);
        if (qaFolder == null)
            return ResponseEntity.status(404).body(Map.of("error", "Quality Control folder not found for this house"));

        // Find or create stage subfolder
        DocFolder stageFolder = folderRepo.findByParentId(qaFolder.getId()).stream()
            .filter(f -> stageName.equals(f.getLabelBg()) || stageNameEn.equals(f.getLabelEn()))
            .findFirst().orElseGet(() -> {
                DocFolder f = new DocFolder();
                f.setCode(stageNameEn.toLowerCase().replaceAll("[^a-z0-9]+", "-"));
                f.setLabelEn(stageNameEn);
                f.setLabelBg(stageName);
                f.setIcon("");
                f.setColor("#4f8ef7");
                f.setSortOrder(0);
                f.setParent(qaFolder);
                return folderRepo.save(f);
            });

        // Find or create today's date subfolder inside the stage folder
        String today = java.time.LocalDate.now(java.time.ZoneId.of("Europe/Sofia")).toString(); // e.g. 2026-08-12
        DocFolder dateFolder = folderRepo.findByParentId(stageFolder.getId()).stream()
            .filter(f -> today.equals(f.getLabelEn()))
            .findFirst().orElseGet(() -> {
                DocFolder f = new DocFolder();
                f.setCode(today);
                f.setLabelEn(today);
                f.setLabelBg(today);
                f.setIcon("");
                f.setColor("#4f8ef7");
                f.setSortOrder(0);
                f.setParent(stageFolder);
                return folderRepo.save(f);
            });

        return ResponseEntity.ok(Map.of("folderId", dateFolder.getId()));
    }
}
