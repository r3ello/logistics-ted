package com.bellgado.logistics_ted.web.audit;

import com.bellgado.logistics_ted.service.AuditLogService;
import com.bellgado.logistics_ted.web.logging.RequestCorrelationFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Generic bitácora capture: records every mutating {@code /api/**} call that reached a real
 * controller method — new endpoints are audited for free. Runs in {@code afterCompletion} so
 * the final status (incl. {@code GlobalExceptionHandler} outcomes) is known, the SecurityContext
 * is still populated, and the MDC seeded by {@link RequestCorrelationFilter} is still present.
 *
 * <p>The SKIP set must mirror any renames of the login endpoints: those are audited explicitly
 * in their controllers (with attempted username), and their request bodies carry passwords, so
 * they stay out of the generic machinery. The Telegram webhook is transport — the actual actions
 * are audited inside {@code LogisticsAgentTools}.
 */
@Component
public class AuditLogInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuditLogInterceptor.class);

    private static final Set<String> MUTATING = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> SKIP = Set.of(
        "/api/login", "/api/worker-login", "/api/logout", "/api/telegram/webhook");

    private final AuditLogService audit;
    private final AuditActionResolver resolver = new AuditActionResolver();

    public AuditLogInterceptor(AuditLogService audit) {
        this.audit = audit;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        if (!(handler instanceof HandlerMethod)) return;
        String method = request.getMethod();
        if (!MUTATING.contains(method)) return;
        String path = request.getRequestURI();
        if (SKIP.contains(path)) return;
        try {
            audit.recordHttpMutation(
                AuditLogService.currentActor(),
                method, path, response.getStatus(),
                resolver.resolve(method, path),
                MDC.get(RequestCorrelationFilter.CLIENT_IP),
                MDC.get(RequestCorrelationFilter.REQUEST_ID));
        } catch (RuntimeException e) {
            log.warn("audit: failed to record {} {}", method, path, e);
        }
    }
}
