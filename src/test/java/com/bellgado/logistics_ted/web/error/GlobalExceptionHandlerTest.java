package com.bellgado.logistics_ted.web.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bellgado.logistics_ted.web.logging.RequestCorrelationFilter;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.ErrorResponseException;

/**
 * Pure unit test — no Spring context. Verifies the {@code {"error": ...}} contract: unexpected
 * errors become a 500 carrying the requestId, framework exceptions keep their status, and security
 * exceptions are rethrown for Spring Security's handlers to produce the 401/403 shapes.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void unexpectedExceptionBecomes500WithRequestId() {
        MDC.put(RequestCorrelationFilter.REQUEST_ID, "req-1");

        ResponseEntity<Map<String, Object>> resp = handler.onUnexpected(new IllegalStateException("boom"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(resp.getBody()).containsEntry("error", "Internal server error");
        assertThat(resp.getBody()).containsEntry("requestId", "req-1");
    }

    @Test
    void frameworkErrorResponseKeepsItsStatus() {
        ResponseEntity<Map<String, Object>> resp =
            handler.onUnexpected(new ErrorResponseException(HttpStatus.NOT_FOUND));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody()).containsKey("error");
    }

    @Test
    void securityExceptionsAreRethrownForSpringSecurityToHandle() {
        AccessDeniedException denied = new AccessDeniedException("nope");

        assertThatThrownBy(() -> handler.onSecurity(denied)).isSameAs(denied);
    }
}
