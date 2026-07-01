package com.bellgado.logistics_ted.observability;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Automatic tracing for the {@code @Service} layer — the "middle" between the request access log
 * ({@code RequestCorrelationFilter}) and the database. Every external call into a {@code @Service}
 * bean is wrapped: entry + normal exit are logged at DEBUG (with capped argument values and the
 * elapsed time), a call slower than the configured threshold is logged at WARN, and any thrown
 * exception is logged once at WARN at the service boundary before being rethrown unchanged.
 *
 * <p>It runs on the request thread, so the {@code requestId} / {@code user} / {@code chatId} already
 * in the MDC are stamped onto every line — service activity is correlated to the originating request
 * for free.
 *
 * <p>Scope is {@code @Service} beans <b>in the business {@code service} package only</b>: this both
 * dodges the {@code final} solver/matrix classes (which CGLIB cannot subclass) and — critically —
 * excludes auth/agent infra like {@code JwtService} / {@code AppUserDetailsService} whose arguments
 * are secrets (a token, a principal). DTOs/value objects are never advised. Pure unit tests that
 * construct services with {@code new} get no proxy, so the route goldens are unaffected.
 *
 * <p>As defense-in-depth, {@link #render} redacts any argument that looks like a JWT / opaque bearer
 * token, so a secret can never reach the log even if a business service starts taking one.
 *
 * <p>Toggle the detail with the {@code com.bellgado.logistics_ted.observability} logger:
 * DEBUG = full entry/exit tracing; INFO/WARN = only slow calls and failures.
 */
@Aspect
@Component
@Slf4j
public class ServiceLoggingAspect {

    private static final int MAX_ARG_CHARS = 120;

    private final long slowCallThresholdMs;

    public ServiceLoggingAspect(
            @Value("${observability.slow-call-threshold-ms:1000}") long slowCallThresholdMs) {
        this.slowCallThresholdMs = slowCallThresholdMs;
    }

    @Around("@within(org.springframework.stereotype.Service)"
        + " && within(com.bellgado.logistics_ted.service..*)")
    public Object traceServiceCall(ProceedingJoinPoint pjp) throws Throwable {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        String where = signature.getDeclaringType().getSimpleName() + "." + signature.getName();

        if (log.isDebugEnabled()) {
            log.debug("[svc] {}({})", where, summariseArgs(pjp.getArgs()));
        }
        long startNanos = System.nanoTime();
        try {
            Object result = pjp.proceed();
            long ms = (System.nanoTime() - startNanos) / 1_000_000;
            if (ms >= slowCallThresholdMs) {
                log.warn("[svc] {} -> {}ms (SLOW, > {}ms)", where, ms, slowCallThresholdMs);
            } else if (log.isDebugEnabled()) {
                log.debug("[svc] {} -> {}ms", where, ms);
            }
            return result;
        } catch (Throwable ex) {
            long ms = (System.nanoTime() - startNanos) / 1_000_000;
            log.warn("[svc] {} -> FAILED after {}ms: {}: {}",
                where, ms, ex.getClass().getSimpleName(), ex.getMessage());
            throw ex;
        }
    }

    /** Compact, length-capped, secret-redacted argument rendering. DEBUG-only. */
    private static String summariseArgs(Object[] args) {
        if (args == null || args.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(render(args[i]));
        }
        return sb.toString();
    }

    private static String render(Object arg) {
        if (arg == null) {
            return "null";
        }
        String s = String.valueOf(arg);
        if (looksLikeToken(s)) {
            return "<redacted>";
        }
        String oneLine = s.replaceAll("\\s+", " ");
        return oneLine.length() <= MAX_ARG_CHARS
            ? oneLine
            : oneLine.substring(0, MAX_ARG_CHARS) + "...(" + oneLine.length() + " chars)";
    }

    /**
     * Defense-in-depth: never let a JWT / opaque bearer token reach the log. Matches the JWT/JWE
     * header prefix and the three-dot-separated base64url token shape.
     */
    private static boolean looksLikeToken(String s) {
        String t = s.trim();
        return t.startsWith("eyJ")
            || t.matches("[A-Za-z0-9_-]{16,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}");
    }
}
