package com.bellgado.logistics_ted.web.importer;

import com.bellgado.logistics_ted.service.importer.EntityImporter;
import com.bellgado.logistics_ted.service.importer.ImportOrchestrator;
import com.bellgado.logistics_ted.service.importer.ImporterRegistry;
import com.bellgado.logistics_ted.web.importer.csv.CsvException;
import com.bellgado.logistics_ted.web.importer.csv.CsvFormat;
import com.bellgado.logistics_ted.web.importer.csv.CsvReader;
import com.bellgado.logistics_ted.web.importer.csv.CsvTable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * CSV sync endpoints, restricted to {@code admin} and {@code importer} — this writes straight into
 * live operational data. {@code importer} is a dedicated role for whoever maintains the client's
 * spreadsheets: it reaches this controller and nothing else, since every other {@code /api/**} path
 * requires ADMIN or USER (see {@code SecurityConfig} and Flyway {@code V4__importer_role.sql}).
 *
 * <p>Parsing happens here, in the web layer, and the orchestrator receives an already-parsed
 * {@link CsvTable}: nothing carrying raw file bytes may appear in a {@code @Service} signature,
 * because {@code ServiceLoggingAspect} renders arguments at DEBUG (DATA_IMPORT_PLAN.md §6.9).
 *
 * <p>Mutating calls here are picked up automatically by {@code AuditLogInterceptor}. The standing
 * rule that request bodies are never captured applies with particular force: an uploaded file can
 * contain anything the client put in a spreadsheet.
 */
@RestController
@RequestMapping("/api/import")
@PreAuthorize("hasAnyRole('ADMIN','IMPORTER')")
public class ImportController {

    private final ImporterRegistry registry;
    private final ImportOrchestrator orchestrator;

    public ImportController(ImporterRegistry registry, ImportOrchestrator orchestrator) {
        this.registry = registry;
        this.orchestrator = orchestrator;
    }

    /** Self-describing catalogue: what can be imported, with which columns, in which order. */
    @GetMapping("/entities")
    public List<Map<String, Object>> entities() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (EntityImporter i : registry.inDependencyOrder()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", i.name());
            m.put("entityType", i.entityType());
            m.put("keyColumn", i.keyColumn());
            m.put("required", new TreeSet<>(i.requiredColumns()));
            Map<String, String> columns = new LinkedHashMap<>();
            new TreeSet<>(i.columns().keySet())
                .forEach(c -> columns.put(c, i.columns().get(c).name().toLowerCase()));
            m.put("columns", columns);
            m.put("dependsOn", i.dependsOn());
            out.add(m);
        }
        return out;
    }

    /** Header-only CSV, BOM-prefixed so Excel opens it as UTF-8 instead of mangling the Cyrillic. */
    @GetMapping("/templates/{entity}")
    public ResponseEntity<byte[]> template(@PathVariable String entity) {
        EntityImporter imp = registry.find(entity).orElse(null);
        if (imp == null) return ResponseEntity.notFound().build();

        List<String> header = new ArrayList<>();
        header.add(imp.keyColumn());
        header.addAll(new TreeSet<>(imp.columns().keySet()));
        byte[] body = ((char) 0xFEFF + String.join(",", header) + "\n").getBytes(StandardCharsets.UTF_8);

        return ResponseEntity.ok()
            .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + imp.name() + ".csv\"")
            .body(body);
    }

    /**
     * Runs one file.
     *
     * @param mode    {@code validate} (default — parses, merges and reports, writing nothing) or
     *                {@code apply}
     * @param onError {@code skip} (default — commit the good rows) or {@code abort} (roll the file back)
     */
    @PostMapping("/{entity}")
    public ResponseEntity<?> importFile(@PathVariable String entity,
                                        @RequestParam("file") MultipartFile file,
                                        @RequestParam(defaultValue = ImportOrchestrator.MODE_VALIDATE) String mode,
                                        @RequestParam(defaultValue = "skip") String onError,
                                        @RequestParam(required = false) String delimiter,
                                        @RequestParam(required = false) String decimal,
                                        Authentication auth) {

        EntityImporter imp = registry.find(entity).orElse(null);
        if (imp == null) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Unknown import entity '" + entity + "'. Known: " + registry.names() + "."));
        }
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No file uploaded."));
        }
        if (!ImportOrchestrator.MODE_APPLY.equals(mode) && !ImportOrchestrator.MODE_VALIDATE.equals(mode)) {
            return ResponseEntity.badRequest().body(Map.of("error", "mode must be 'validate' or 'apply'."));
        }

        try {
            CsvTable table = CsvReader.read(file.getBytes(), CsvFormat.of(delimiter, decimal));
            ImportReport report = orchestrator.run(
                imp, table, mode, onError, file.getOriginalFilename(),
                auth == null ? null : auth.getName());
            return ResponseEntity.ok(report);
        } catch (CsvException e) {
            // File-level failure: there is no report to return, only the reason.
            return ResponseEntity.badRequest().body(Map.of(
                "error", e.getMessage(), "code", e.getCode().name()));
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Could not read the uploaded file."));
        }
    }
}
