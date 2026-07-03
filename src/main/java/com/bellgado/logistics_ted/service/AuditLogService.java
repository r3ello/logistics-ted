package com.bellgado.logistics_ted.service;

import com.bellgado.logistics_ted.domain.AuditLog;
import com.bellgado.logistics_ted.repository.AuditLogRepository;
import com.bellgado.logistics_ted.security.AppUserDetailsService.AuthenticatedUser;
import com.bellgado.logistics_ted.web.audit.AuditActionResolver.ResolvedAction;
import com.bellgado.logistics_ted.web.dto.AuditLogDto;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bitácora writer/reader. Every write runs in {@code REQUIRES_NEW} so an audit failure can
 * never poison the caller's main flow (same invariant as {@link OrderHistoryService}); callers
 * additionally wrap calls in {@code catch (RuntimeException)}. Rows are append-only — the DB
 * blocks UPDATE via trigger — so this service never re-saves a persisted entity and reads map
 * to DTOs inside the transaction.
 */
@Service
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    public static final String SOURCE_API = "api";
    public static final String SOURCE_TELEGRAM = "telegram";

    private final AuditLogRepository repo;

    public AuditLogService(AuditLogRepository repo) {
        this.repo = repo;
    }

    /** Who performed the action. Exactly one identity flavour is populated per actor type. */
    public record Actor(String actorType, Integer appUserId, Integer workerId,
                        Long telegramChatId, String username, String role) {
        public static final Actor ANONYMOUS = new Actor("anonymous", null, null, null, null, null);
    }

    /** Resolves the current HTTP caller from the SecurityContext. Never throws. */
    public static Actor currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AuthenticatedUser u)) {
            return Actor.ANONYMOUS;
        }
        return actorOf(u);
    }

    /**
     * Maps a principal to an actor. Roles {@code admin}/{@code user} live in {@code app_user};
     * every other role label (crew_leader, crew_manager, worker) is Worker-backed, where
     * {@link AuthenticatedUser#getUserId()} is a {@code worker.id} — not an app_user id.
     */
    public static Actor actorOf(AuthenticatedUser u) {
        String role = u.getRoleLabel();
        boolean appUser = "admin".equalsIgnoreCase(role) || "user".equalsIgnoreCase(role);
        return appUser
            ? new Actor("app_user", u.getUserId(), null, null, u.getUsername(), role)
            : new Actor("worker", null, u.getUserId(), null, u.getUsername(), role);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordHttpMutation(Actor actor, String method, String path, int status,
                                   ResolvedAction resolved, String clientIp, String requestId) {
        AuditLog row = base(actor, SOURCE_API);
        row.setAction(resolved.action());
        row.setEntityType(resolved.entityType());
        row.setEntityId(resolved.entityId());
        fillHttp(row, method, path, status, clientIp, requestId);
        repo.save(row);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordLoginSuccess(Actor actor, String endpoint, String clientIp, String requestId) {
        AuditLog row = base(actor, SOURCE_API);
        row.setAction("login_success");
        row.setEntityType("auth");
        fillHttp(row, "POST", endpoint, 200, clientIp, requestId);
        repo.save(row);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordLoginFailure(String endpoint, String attemptedUsername, String reason,
                                   int status, String clientIp, String requestId) {
        AuditLog row = base(Actor.ANONYMOUS, SOURCE_API);
        row.setUsername(attemptedUsername);
        row.setAction("login_failure");
        row.setEntityType("auth");
        if (reason != null) {
            row.setDetailsJson(Map.of("reason", reason));
        }
        fillHttp(row, "POST", endpoint, status, clientIp, requestId);
        repo.save(row);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordTelegramAction(Long chatId, String action, String entityType,
                                     String entityId, Map<String, Object> details) {
        Actor actor = chatId != null
            ? new Actor("telegram", null, null, chatId, null, null)
            : Actor.ANONYMOUS;
        AuditLog row = base(actor, chatId != null ? SOURCE_TELEGRAM : SOURCE_API);
        row.setAction(action);
        row.setEntityType(entityType);
        row.setEntityId(entityId);
        if (details != null && !details.isEmpty()) {
            row.setDetailsJson(details);
        }
        repo.save(row);
    }

    /** Sentinel upper bound for "no date filter" — avoids a `:param IS NULL` check that
     *  PostgreSQL cannot type (bare timestamp parameters break its type inference). */
    private static final Instant FAR_FUTURE = Instant.parse("9999-12-31T00:00:00Z");

    @Transactional(readOnly = true)
    public Page<AuditLogDto> search(String username, String action, String entityType,
                                    Instant from, Instant to, Pageable pageable) {
        return repo.search(blankToNull(username), blankToNull(action), blankToNull(entityType),
                from == null ? Instant.EPOCH : from,
                to == null ? FAR_FUTURE : to,
                pageable)
            .map(AuditLogService::toDto);
    }

    private static AuditLog base(Actor actor, String source) {
        AuditLog row = new AuditLog();
        row.setAt(Instant.now());
        row.setActorType(actor.actorType());
        row.setAppUserId(actor.appUserId());
        row.setWorkerId(actor.workerId());
        row.setTelegramChatId(actor.telegramChatId());
        row.setUsername(actor.username());
        row.setRole(actor.role());
        row.setSource(source);
        return row;
    }

    private static void fillHttp(AuditLog row, String method, String path, int status,
                                 String clientIp, String requestId) {
        row.setHttpMethod(method);
        row.setHttpPath(path);
        row.setHttpStatus(status);
        row.setClientIp(clientIp);
        row.setRequestId(requestId);
    }

    private static AuditLogDto toDto(AuditLog a) {
        return new AuditLogDto(a.getId(), a.getAt(), a.getActorType(), a.getUsername(), a.getRole(),
            a.getTelegramChatId(), a.getSource(), a.getAction(), a.getEntityType(), a.getEntityId(),
            a.getHttpMethod(), a.getHttpPath(), a.getHttpStatus(), a.getClientIp(), a.getRequestId(),
            a.getDetailsJson());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
