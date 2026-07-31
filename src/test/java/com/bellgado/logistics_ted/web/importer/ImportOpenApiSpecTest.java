package com.bellgado.logistics_ted.web.importer;

import static org.assertj.core.api.Assertions.assertThat;

import com.bellgado.logistics_ted.service.importer.ImportErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Guards the hand-written OpenAPI document against the two ways it can go wrong.
 *
 * <p><b>Leaking.</b> The whole reason the specification is written by hand rather than generated is
 * that the external client integrating with the CSV import must not learn the internal API the
 * dashboard consumes. {@link #documentsNothingOutsideTheImportSurface()} makes that structural: the
 * build fails if anyone ever documents a path outside {@code /api/import}.
 *
 * <p><b>Drift.</b> A hand-written document can fall behind the code. The remaining tests pin it to
 * the controllers' actual mappings, to {@link ImportErrorCode} and to {@link ImportReport}, so
 * adding an endpoint, a code or a report field without documenting it is a failing test rather than
 * a client integration bug.
 *
 * <p>Needs no database — it reads the resource off the classpath.
 */
class ImportOpenApiSpecTest {

    private static final String SPEC = "openapi/import-openapi.yaml";

    /** The only non-import path the document is allowed to describe: obtaining a token. */
    private static final String AUTH_PATH = "/api/login";

    private static Map<String, Object> spec;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void loadSpec() throws IOException {
        try (InputStream in = new ClassPathResource(SPEC).getInputStream()) {
            String yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            spec = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
        }
    }

    @Test
    void isAWellFormedOpenApiDocument() {
        assertThat(spec).isNotNull();
        assertThat((String) spec.get("openapi")).startsWith("3.");
        assertThat(spec).containsKeys("info", "paths", "components");
        assertThat(paths()).isNotEmpty();
    }

    // ── the isolation invariant ───────────────────────────────────────────────

    @Test
    void documentsNothingOutsideTheImportSurface() {
        Set<String> offenders = new TreeSet<>();
        for (String path : paths().keySet()) {
            if (!path.startsWith("/api/import") && !path.equals(AUTH_PATH)) {
                offenders.add(path);
            }
        }
        assertThat(offenders)
            .withFailMessage("""
                The import API document describes %s, which is outside /api/import.

                This document is published to an external client. Nothing internal to the dashboard \
                may appear in it. Either remove the path, or — if it genuinely belongs to the import \
                contract — move the endpoint under /api/import.""", offenders)
            .isEmpty();
    }

    // ── drift guards ──────────────────────────────────────────────────────────

    @Test
    void documentsEveryEndpointTheImportControllersExpose() {
        // Without this the containsAll below would pass vacuously if the reflection ever broke.
        assertThat(mappedPaths()).as("paths discovered by reflection").isNotEmpty();

        assertThat(paths().keySet())
            .withFailMessage("""
                The import controllers expose %s but the document describes %s.

                An endpoint the client cannot discover may as well not exist. Add it to %s.""",
                mappedPaths(), paths().keySet(), SPEC)
            .containsAll(mappedPaths());
    }

    @Test
    void listsEveryErrorCode() {
        Set<String> documented = new LinkedHashSet<>(enumValuesOf("ImportErrorCode"));
        Set<String> actual = new LinkedHashSet<>();
        for (ImportErrorCode c : ImportErrorCode.values()) {
            actual.add(c.name());
        }
        assertThat(documented)
            .withFailMessage("Documented error codes %s do not match ImportErrorCode %s.",
                documented, actual)
            .isEqualTo(actual);
    }

    @Test
    void describesEveryFieldOfTheReport() {
        Set<String> documented = propertiesOf("ImportReport");
        Set<String> actual = new TreeSet<>();
        for (RecordComponent rc : ImportReport.class.getRecordComponents()) {
            actual.add(rc.getName());
        }
        assertThat(new TreeSet<>(documented))
            .withFailMessage("""
                The documented ImportReport schema is %s but the record is %s.

                A client reading this document builds against the documented shape.""",
                new TreeSet<>(documented), actual)
            .isEqualTo(actual);
    }

    @Test
    void everyOperationDeclaresItsResponses() {
        for (Map.Entry<String, Object> path : paths().entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> operations = (Map<String, Object>) path.getValue();
            for (Map.Entry<String, Object> op : operations.entrySet()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = (Map<String, Object>) op.getValue();
                assertThat(body)
                    .withFailMessage("%s %s declares no responses.", op.getKey(), path.getKey())
                    .containsKey("responses");
                assertThat(body)
                    .withFailMessage("%s %s has no summary.", op.getKey(), path.getKey())
                    .containsKey("summary");
            }
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static Map<String, Object> paths() {
        return (Map<String, Object>) spec.get("paths");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> schema(String name) {
        Map<String, Object> components = (Map<String, Object>) spec.get("components");
        Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
        Map<String, Object> found = (Map<String, Object>) schemas.get(name);
        assertThat(found).withFailMessage("No schema '%s' in %s.", name, SPEC).isNotNull();
        return found;
    }

    @SuppressWarnings("unchecked")
    private static List<String> enumValuesOf(String schemaName) {
        return (List<String>) schema(schemaName).get("enum");
    }

    @SuppressWarnings("unchecked")
    private static Set<String> propertiesOf(String schemaName) {
        Map<String, Object> properties = (Map<String, Object>) schema(schemaName).get("properties");
        return properties.keySet();
    }

    /** Every path the import controllers actually map, assembled from their annotations. */
    private static Set<String> mappedPaths() {
        Set<String> out = new TreeSet<>();
        for (Class<?> controller : List.of(ImportController.class, ImportDocsController.class)) {
            String base = controller.getAnnotation(RequestMapping.class).value()[0];
            for (Method m : controller.getDeclaredMethods()) {
                GetMapping get = m.getAnnotation(GetMapping.class);
                PostMapping post = m.getAnnotation(PostMapping.class);
                if (get != null)  Arrays.stream(get.value()).forEach(v -> out.add(base + v));
                if (post != null) Arrays.stream(post.value()).forEach(v -> out.add(base + v));
            }
        }
        return out;
    }
}
