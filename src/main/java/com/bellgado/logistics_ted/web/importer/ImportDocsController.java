package com.bellgado.logistics_ted.web.importer;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Serves the OpenAPI contract for the CSV import API to the external client integrating with it.
 *
 * <p><b>Why the specification is hand-written and not generated.</b> A generator (springdoc and
 * friends) scans every controller in the application, so the complete API — including the internal
 * endpoints the dashboard consumes — exists in memory and the only thing keeping it private is that
 * no route happens to serve it. Given {@code SecurityConfig} ends in {@code anyRequest().permitAll()},
 * a generator's default {@code /v3/api-docs} would be world-readable the moment the dependency
 * landed. Writing the document by hand makes the isolation structural rather than configurational:
 * only what is in the file can be published. It also documents things a generator cannot infer —
 * the CSV format, the merge outcomes, and the error-code catalogue.
 *
 * <p>The trade-off is drift, which {@code ImportOpenApiSpecTest} guards: it fails if the document
 * describes a path outside the import surface, omits one the controllers expose, or falls behind
 * {@code ImportErrorCode} / {@code ImportReport}.
 *
 * <p><b>Why it lives here and not in {@code static/}.</b> Anything under {@code static/} is served by
 * the resource handler and falls through to {@code anyRequest().permitAll()} — the document would be
 * public. Mapping it under {@code /api/import} instead puts it behind the same authorization as the
 * endpoints it describes: {@code admin} or {@code importer}, and nothing else.
 */
@RestController
@RequestMapping("/api/import")
@PreAuthorize("hasAnyRole('ADMIN','IMPORTER')")
public class ImportDocsController {

    private static final String SPEC = "openapi/import-openapi.yaml";
    private static final String YAML_MEDIA_TYPE = "application/yaml;charset=UTF-8";

    private final ObjectMapper json;

    /** Read once on first request and reused — the file ships inside the jar and cannot change. */
    private volatile String cachedYaml;
    private volatile Map<String, Object> cachedTree;

    public ImportDocsController(ObjectMapper json) {
        this.json = json;
    }

    /** The contract as authored. Feed it to a client generator if you like. */
    @GetMapping(value = "/openapi.yaml", produces = YAML_MEDIA_TYPE)
    public ResponseEntity<String> yaml() throws IOException {
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(YAML_MEDIA_TYPE))
            .body(specYaml());
    }

    /**
     * The same document as JSON, for consumers that would otherwise need a YAML parser — the docs
     * viewer among them. Converted rather than maintained separately, so the two cannot disagree.
     */
    @GetMapping(value = "/openapi.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> asJson() throws IOException {
        return specTree();
    }

    private String specYaml() throws IOException {
        String local = cachedYaml;
        if (local == null) {
            try (InputStream in = new ClassPathResource(SPEC).getInputStream()) {
                local = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            cachedYaml = local;
        }
        return local;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> specTree() throws IOException {
        Map<String, Object> local = cachedTree;
        if (local == null) {
            // SafeConstructor: the document is ours, but a YAML loader that can instantiate
            // arbitrary classes has no business being in a web request path.
            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            local = yaml.load(specYaml());
            // Round-trip through Jackson so the response is plain JSON types, not SnakeYAML's.
            local = json.convertValue(local, Map.class);
            cachedTree = local;
        }
        return local;
    }
}
