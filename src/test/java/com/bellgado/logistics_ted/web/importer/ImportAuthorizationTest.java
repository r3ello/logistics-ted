package com.bellgado.logistics_ted.web.importer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Pins who may reach the CSV import API.
 *
 * <p>The {@code importer} role exists to separate duties: the import is operated by whoever
 * maintains the client's spreadsheets, and that account must not reach the dispatcher/admin surface.
 * Narrowing these annotations back to {@code hasRole('ADMIN')} would lock that operator out;
 * widening them to {@code authenticated()} or {@code hasAnyRole(...,'USER')} would hand write access
 * to live operational data to every dashboard user.
 *
 * <p><b>What this does not cover.</b> It asserts the controllers' declared authorization, not the
 * filter chain. The matching {@code SecurityConfig} rule —
 * {@code .requestMatchers("/api/import/**").hasAnyRole("ADMIN", "IMPORTER")}, which must stay
 * <i>above</i> the generic {@code /api/**} matcher — has no test because the project has no
 * MockMvc/{@code @SpringBootTest} infrastructure that runs without PostgreSQL. Verify it by hand
 * when changing the chain: an {@code importer} token must get 403 on {@code GET /api/houses}.
 */
class ImportAuthorizationTest {

    private static final String EXPECTED = "hasAnyRole('ADMIN','IMPORTER')";

    /**
     * The contract document covers the export too, so the read-only {@code exporter} role may read
     * it — but only it: {@link ImportController} itself stays admin + importer.
     */
    private static final String EXPECTED_DOCS = "hasAnyRole('ADMIN','IMPORTER','EXPORTER')";

    @Test
    void importEndpointsAreOpenToAdminsAndImportersOnly() {
        assertDeclares(ImportController.class, EXPECTED);
    }

    @Test
    void theContractIsReadableByBothExternalRoles() {
        assertDeclares(ImportDocsController.class, EXPECTED_DOCS);
    }

    private static void assertDeclares(Class<?> controller, String expected) {
        PreAuthorize annotation = controller.getAnnotation(PreAuthorize.class);

        assertThat(annotation)
            .withFailMessage("%s has no @PreAuthorize. Every import controller must declare one — "
                + "the SecurityConfig matcher alone would let any ADMIN or USER token through.",
                controller.getSimpleName())
            .isNotNull();

        assertThat(annotation.value().replace(" ", "").replace("\"", "'"))
            .withFailMessage("%s declares @PreAuthorize(\"%s\"); expected \"%s\".",
                controller.getSimpleName(), annotation.value(), expected)
            .isEqualTo(expected);
    }
}
