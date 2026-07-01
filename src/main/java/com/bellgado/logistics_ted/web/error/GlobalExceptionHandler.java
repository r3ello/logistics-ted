package com.bellgado.logistics_ted.web.error;

import com.bellgado.logistics_ted.web.logging.RequestCorrelationFilter;
import jakarta.validation.ConstraintViolationException;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Single place where uncaught exceptions become a consistent JSON error response, preserving the
 * Node-parity {@code {"error": ...}} body shape.
 *
 * <p>Two invariants:
 * <ul>
 *   <li><b>Security exceptions are rethrown</b> so Spring Security's existing
 *       {@code JsonAuthenticationEntryPoint} / {@code JsonAccessDeniedHandler} keep producing the
 *       {@code {"error":"Unauthorized"}} / {@code {"error":"Forbidden"}} bodies untouched.</li>
 *   <li><b>Framework exceptions keep their HTTP status.</b> Most Spring MVC exceptions implement
 *       {@link ErrorResponse} (400 malformed body, 404, 405, 415, ...); the catch-all honours that
 *       status and only reshapes the body — only genuinely unexpected errors become a 500.</li>
 * </ul>
 * The 5xx path logs at ERROR with the {@code requestId} so a failure the user reports can be grepped
 * straight out of the access/error log.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> onValidation(MethodArgumentNotValidException ex) {
        FieldError fe = ex.getBindingResult().getFieldError();
        String msg = fe != null
            ? fe.getField() + " " + fe.getDefaultMessage()
            : "Validation failed.";
        log.warn("400 validation: {}", msg);
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> onConstraint(ConstraintViolationException ex) {
        log.warn("400 constraint: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }

    /**
     * Let authentication/authorization failures fall through to Spring Security's handlers so the
     * existing 401/403 JSON shapes are preserved. Rethrowing makes the @ExceptionHandler resolver
     * yield and the exception propagates to the ExceptionTranslationFilter.
     */
    @ExceptionHandler({AccessDeniedException.class, AuthenticationException.class})
    public void onSecurity(RuntimeException ex) {
        throw ex;
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> onUnexpected(Exception ex) {
        if (ex instanceof ErrorResponse er) {
            // Framework exception with a defined status (400/404/405/415/...). Preserve the status;
            // only reshape the body to the {"error": ...} contract.
            HttpStatusCode status = er.getStatusCode();
            String detail = er.getBody().getDetail();
            String msg = detail != null ? detail : status.toString();
            if (status.is5xxServerError()) {
                log.error("{} framework error (requestId={})", status.value(), requestId(), ex);
            } else {
                log.warn("{} {}", status.value(), msg);
            }
            return ResponseEntity.status(status).body(body(msg, status.is5xxServerError()));
        }
        log.error("500 unhandled exception (requestId={})", requestId(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(body("Internal server error", true));
    }

    private static Map<String, Object> body(String message, boolean attachRequestId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        String requestId = requestId();
        if (attachRequestId && requestId != null) {
            body.put("requestId", requestId);
        }
        return body;
    }

    private static String requestId() {
        return MDC.get(RequestCorrelationFilter.REQUEST_ID);
    }
}
