package com.bellgado.logistics_ted.web.exporter;

import com.bellgado.logistics_ted.service.AuditLogService;
import com.bellgado.logistics_ted.service.exporter.EntityExporter;
import com.bellgado.logistics_ted.service.exporter.ExportColumn;
import com.bellgado.logistics_ted.service.exporter.ExportException;
import com.bellgado.logistics_ted.service.exporter.ExportFilter;
import com.bellgado.logistics_ted.service.exporter.ExportQuery;
import com.bellgado.logistics_ted.service.exporter.ExporterRegistry;
import com.bellgado.logistics_ted.web.importer.csv.CsvException;
import com.bellgado.logistics_ted.web.importer.csv.CsvFormat;
import com.bellgado.logistics_ted.web.logging.RequestCorrelationFilter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * CSV export, restricted to {@code admin} and {@code exporter}. {@code exporter} is the read-only
 * counterpart of {@code importer}: an external account that reaches this controller (and the
 * integration contract) and nothing else — see {@code SecurityConfig} and Flyway
 * {@code V18__exporter_role.sql}.
 *
 * <p>Everything here is generic over {@link EntityExporter}: a new dataset is one {@code @Component}
 * and shows up in {@code /api/export/entities} and under {@code /api/export/{name}} with no change
 * to this class.
 *
 * <p>Every successful export is written to the audit log (dataset, filters, columns, row count),
 * because {@code AuditLogInterceptor} only captures mutating calls and this one moves bulk personal
 * data out of the system.
 */
@RestController
@RequestMapping("/api/export")
@PreAuthorize("hasAnyRole('ADMIN','EXPORTER')")
public class ExportController {

    private static final Logger log = LoggerFactory.getLogger(ExportController.class);

    /** Request parameters that shape the file rather than filter the rows. */
    static final Set<String> FORMAT_PARAMS = Set.of("columns", "delimiter", "decimal", "timestamps");

    private final ExporterRegistry registry;
    private final AuditLogService audit;

    public ExportController(ExporterRegistry registry, AuditLogService audit) {
        this.registry = registry;
        this.audit = audit;
    }

    /** Self-describing catalogue: every dataset, its filters and its selectable columns. */
    @GetMapping("/entities")
    public List<Map<String, Object>> entities() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (EntityExporter<?> e : registry.all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", e.name());
            m.put("description", e.description());
            List<Map<String, Object>> filters = new ArrayList<>();
            for (ExportFilter f : e.filters()) {
                Map<String, Object> fm = new LinkedHashMap<>();
                fm.put("name", f.name());
                fm.put("type", f.type().name().toLowerCase());
                fm.put("required", f.required());
                fm.put("description", f.description());
                filters.add(fm);
            }
            m.put("filters", filters);
            List<Map<String, Object>> columns = new ArrayList<>();
            for (ExportColumn<?> c : e.columns()) {
                Map<String, Object> cm = new LinkedHashMap<>();
                cm.put("name", c.name());
                cm.put("type", c.type().name().toLowerCase());
                cm.put("default", c.defaultIncluded());
                cm.put("description", c.description());
                columns.add(cm);
            }
            m.put("columns", columns);
            out.add(m);
        }
        return out;
    }

    /**
     * Streams one dataset as CSV.
     *
     * @param params the dataset's filters plus {@code columns} (comma-separated, in output order;
     *               omitted = the dataset's default columns), {@code delimiter}, {@code decimal} and
     *               {@code timestamps} ({@code iso} | {@code local}). Any other parameter is a 400,
     *               so a misspelt filter never silently widens the export.
     */
    @GetMapping("/{entity}")
    public ResponseEntity<?> export(@PathVariable String entity,
                                    @RequestParam Map<String, String> params) {
        EntityExporter<?> exporter = registry.find(entity).orElse(null);
        if (exporter == null) {
            return ResponseEntity.status(404).body(Map.of(
                "error", "Unknown export dataset '" + entity + "'. Known: " + registry.names() + "."));
        }
        try {
            return render(exporter, params);
        } catch (ExportException | CsvException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private <R> ResponseEntity<byte[]> render(EntityExporter<R> exporter, Map<String, String> params) {
        Set<String> filterNames = exporter.filters().stream().map(ExportFilter::name).collect(Collectors.toSet());
        List<String> unknown = params.keySet().stream()
            .filter(p -> !FORMAT_PARAMS.contains(p) && !filterNames.contains(p))
            .sorted().toList();
        if (!unknown.isEmpty()) {
            throw new ExportException("Unknown parameter(s) " + unknown + ". Filters for '" + exporter.name()
                + "': " + filterNames.stream().sorted().toList() + "; format: " + FORMAT_PARAMS.stream().sorted().toList() + ".");
        }

        CsvFormat format = CsvFormat.of(params.get("delimiter"), params.get("decimal"));
        if (format.decimalComma() && format.delimiter() == ',') {
            throw new ExportException("decimal=comma needs delimiter=semicolon (or tab).");
        }
        CsvExportWriter.TimeFormat time = CsvExportWriter.TimeFormat.of(params.get("timestamps"));
        List<ExportColumn<R>> columns = selectColumns(exporter.columns(), params.get("columns"));
        ExportQuery query = ExportQuery.parse(exporter.filters(), params);

        List<R> rows = exporter.fetch(query);
        byte[] body = CsvExportWriter.write(columns, rows, format, time);

        recordAudit(exporter.name(), query, columns, rows.size());
        return ResponseEntity.ok()
            .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(exporter.fileName(query)).build().toString())
            .header("X-Export-Rows", Integer.toString(rows.size()))
            .body(body);
    }

    /** Picks and orders the requested columns; blank = the defaults. Unknown names are a 400. */
    static <R> List<ExportColumn<R>> selectColumns(List<ExportColumn<R>> all, String requested) {
        if (requested == null || requested.isBlank()) {
            return all.stream().filter(ExportColumn::defaultIncluded).toList();
        }
        Map<String, ExportColumn<R>> byName = new LinkedHashMap<>();
        all.forEach(c -> byName.put(c.name(), c));
        Set<String> names = new LinkedHashSet<>();
        for (String raw : requested.split(",")) {
            String n = raw.trim().toLowerCase();
            if (!n.isEmpty()) names.add(n);
        }
        List<String> unknown = names.stream().filter(n -> !byName.containsKey(n)).toList();
        if (!unknown.isEmpty()) {
            throw new ExportException("Unknown column(s) " + unknown + ". Available: " + byName.keySet() + ".");
        }
        if (names.isEmpty()) {
            throw new ExportException("columns is empty.");
        }
        return names.stream().map(byName::get).toList();
    }

    private void recordAudit(String dataset, ExportQuery query, List<? extends ExportColumn<?>> columns, int rows) {
        Map<String, Object> filters = new LinkedHashMap<>();
        query.asMap().forEach((k, v) -> filters.put(k, String.valueOf(v)));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("filters", filters);
        details.put("columns", columns.stream().map(ExportColumn::name).toList());
        details.put("rows", rows);
        try {
            audit.recordExport(AuditLogService.currentActor(), "/api/export/" + dataset, dataset, details,
                MDC.get(RequestCorrelationFilter.CLIENT_IP), MDC.get(RequestCorrelationFilter.REQUEST_ID));
        } catch (RuntimeException e) {
            log.warn("audit: failed to record export of {}", dataset, e);
        }
    }
}
