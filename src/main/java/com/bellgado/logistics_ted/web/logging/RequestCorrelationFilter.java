package com.bellgado.logistics_ted.web.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Outermost filter (before Spring Security). Stamps a correlation id and request metadata into
 * the SLF4J MDC so every log line emitted while handling the request — including lines from deep
 * in the service layer and from {@code GlobalExceptionHandler} — carries the same {@code requestId}.
 * {@code user}/{@code role} are added later by {@code JwtAuthenticationFilter} once the bearer token
 * is validated, and are visible here because that filter runs nested inside this one.
 *
 * <p>Emits a single access-log line per request (method, path, status, latency). The MDC is fully
 * cleared in a {@code finally} so nothing leaks across pooled request threads.
 *
 * <p>Registered explicitly at {@code Ordered.HIGHEST_PRECEDENCE} (below Spring Security's chain) by
 * {@code WebObservabilityConfig} so the {@code requestId} is present for <em>every</em> log line of
 * the request, including any emitted from within the security filters.
 */
@Slf4j
public class RequestCorrelationFilter extends OncePerRequestFilter {

    /** MDC key for the per-request correlation id. Read by {@code GlobalExceptionHandler}. */
    public static final String REQUEST_ID = "requestId";
    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    /** MDC key for the caller IP (X-Forwarded-For aware). Read by the audit-log layer. */
    public static final String CLIENT_IP = "clientIp";

    private static final String METHOD = "httpMethod";
    private static final String PATH = "httpPath";
    private static final String STATUS = "httpStatus";
    private static final String DURATION = "durationMs";

    private static final Set<String> STATIC_EXT = Set.of(
        "html", "js", "css", "png", "jpg", "jpeg", "gif", "svg", "ico", "map", "woff", "woff2", "ttf");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (!StringUtils.hasText(requestId)) {
            requestId = UUID.randomUUID().toString();
        }
        String method = request.getMethod();
        String path = request.getRequestURI();

        MDC.put(REQUEST_ID, requestId);
        MDC.put(METHOD, method);
        MDC.put(PATH, path);
        MDC.put(CLIENT_IP, clientIp(request));
        response.setHeader(REQUEST_ID_HEADER, requestId);

        boolean loggable = isLoggable(path);
        long startNanos = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            if (loggable) {
                long ms = (System.nanoTime() - startNanos) / 1_000_000;
                int status = response.getStatus();
                MDC.put(STATUS, Integer.toString(status));
                MDC.put(DURATION, Long.toString(ms));
                if (status >= 500) {
                    log.warn("{} {} -> {} in {}ms", method, path, status, ms);
                } else {
                    log.info("{} {} -> {} in {}ms", method, path, status, ms);
                }
            }
            MDC.clear();
        }
    }

    /** Skip health checks, the SPA shell and static assets so the access log stays signal-rich. */
    private static boolean isLoggable(String path) {
        if (path.startsWith("/actuator") || path.equals("/") || path.startsWith("/leaflet")) {
            return false;
        }
        int dot = path.lastIndexOf('.');
        return dot < 0 || !STATIC_EXT.contains(path.substring(dot + 1).toLowerCase());
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            int comma = forwarded.indexOf(',');
            return comma > 0 ? forwarded.substring(0, comma).trim() : forwarded.trim();
        }
        return request.getRemoteAddr();
    }
}
