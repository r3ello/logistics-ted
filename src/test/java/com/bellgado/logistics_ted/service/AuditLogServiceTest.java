package com.bellgado.logistics_ted.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bellgado.logistics_ted.domain.AuditLog;
import com.bellgado.logistics_ted.repository.AuditLogRepository;
import com.bellgado.logistics_ted.security.AppUserDetailsService.AuthenticatedUser;
import com.bellgado.logistics_ted.service.AuditLogService.Actor;
import com.bellgado.logistics_ted.web.audit.AuditActionResolver.ResolvedAction;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Locks down the polymorphic actor resolution (app_user vs worker-backed vs anonymous) and the
 * field mapping of each record method. Repository is mocked — no Postgres needed.
 */
class AuditLogServiceTest {

    private AuditLogRepository repo;
    private AuditLogService service;

    @BeforeEach
    void setUp() {
        repo = mock(AuditLogRepository.class);
        when(repo.save(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new AuditLogService(repo);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void seedPrincipal(Integer userId, String username, String roleLabel) {
        AuthenticatedUser principal = new AuthenticatedUser(userId, username, "", roleLabel,
            List.of(new SimpleGrantedAuthority("ROLE_" + roleLabel.toUpperCase())));
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private AuditLog savedRow() {
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(repo).save(captor.capture());
        return captor.getValue();
    }

    // ── currentActor ────────────────────────────────────────────────────────────

    @Test
    void adminPrincipalMapsToAppUserActor() {
        seedPrincipal(1, "admin", "admin");
        Actor a = AuditLogService.currentActor();
        assertThat(a.actorType()).isEqualTo("app_user");
        assertThat(a.appUserId()).isEqualTo(1);
        assertThat(a.workerId()).isNull();
        assertThat(a.username()).isEqualTo("admin");
        assertThat(a.role()).isEqualTo("admin");
    }

    @Test
    void plainUserPrincipalMapsToAppUserActor() {
        seedPrincipal(2, "user", "user");
        assertThat(AuditLogService.currentActor().actorType()).isEqualTo("app_user");
    }

    @Test
    void crewLeaderPrincipalMapsToWorkerActor() {
        // For crew leaders the principal userId is a worker.id, NOT an app_user id.
        seedPrincipal(42, "ivan", "crew_leader");
        Actor a = AuditLogService.currentActor();
        assertThat(a.actorType()).isEqualTo("worker");
        assertThat(a.workerId()).isEqualTo(42);
        assertThat(a.appUserId()).isNull();
        assertThat(a.role()).isEqualTo("crew_leader");
    }

    @Test
    void workerTokenPrincipalMapsToWorkerActor() {
        seedPrincipal(7, "petar", "worker");
        Actor a = AuditLogService.currentActor();
        assertThat(a.actorType()).isEqualTo("worker");
        assertThat(a.workerId()).isEqualTo(7);
    }

    @Test
    void emptyContextMapsToAnonymous() {
        assertThat(AuditLogService.currentActor()).isEqualTo(Actor.ANONYMOUS);
    }

    // ── record methods ──────────────────────────────────────────────────────────

    @Test
    void recordHttpMutationMapsAllFields() {
        Actor actor = new Actor("app_user", 1, null, null, "admin", "admin");
        service.recordHttpMutation(actor, "DELETE", "/api/houses/12", 200,
            new ResolvedAction("delete", "house", "12"), "10.0.0.5", "req-1");

        AuditLog row = savedRow();
        assertThat(row.getAt()).isNotNull();
        assertThat(row.getActorType()).isEqualTo("app_user");
        assertThat(row.getAppUserId()).isEqualTo(1);
        assertThat(row.getUsername()).isEqualTo("admin");
        assertThat(row.getSource()).isEqualTo("api");
        assertThat(row.getAction()).isEqualTo("delete");
        assertThat(row.getEntityType()).isEqualTo("house");
        assertThat(row.getEntityId()).isEqualTo("12");
        assertThat(row.getHttpMethod()).isEqualTo("DELETE");
        assertThat(row.getHttpPath()).isEqualTo("/api/houses/12");
        assertThat(row.getHttpStatus()).isEqualTo(200);
        assertThat(row.getClientIp()).isEqualTo("10.0.0.5");
        assertThat(row.getRequestId()).isEqualTo("req-1");
        assertThat(row.getDetailsJson()).isNull();
    }

    @Test
    void recordLoginSuccessMapsActorAndEndpoint() {
        service.recordLoginSuccess(new Actor("worker", null, 42, null, "ivan", "crew_leader"),
            "/api/login", "10.0.0.9", "req-2");

        AuditLog row = savedRow();
        assertThat(row.getAction()).isEqualTo("login_success");
        assertThat(row.getEntityType()).isEqualTo("auth");
        assertThat(row.getWorkerId()).isEqualTo(42);
        assertThat(row.getHttpPath()).isEqualTo("/api/login");
        assertThat(row.getHttpStatus()).isEqualTo(200);
    }

    @Test
    void recordLoginFailureKeepsAttemptedUsernameOnAnonymousActor() {
        service.recordLoginFailure("/api/worker-login", "mallory", "bad_credentials", 401,
            "10.0.0.9", "req-3");

        AuditLog row = savedRow();
        assertThat(row.getActorType()).isEqualTo("anonymous");
        assertThat(row.getAppUserId()).isNull();
        assertThat(row.getWorkerId()).isNull();
        assertThat(row.getUsername()).isEqualTo("mallory");
        assertThat(row.getAction()).isEqualTo("login_failure");
        assertThat(row.getHttpStatus()).isEqualTo(401);
        assertThat(row.getDetailsJson()).isEqualTo(Map.of("reason", "bad_credentials"));
    }

    @Test
    void recordTelegramActionUsesChatIdActor() {
        service.recordTelegramAction(555L, "choose", "order", "abc", Map.of("objective", "balanced"));

        AuditLog row = savedRow();
        assertThat(row.getActorType()).isEqualTo("telegram");
        assertThat(row.getSource()).isEqualTo("telegram");
        assertThat(row.getTelegramChatId()).isEqualTo(555L);
        assertThat(row.getUsername()).isNull();
        assertThat(row.getAction()).isEqualTo("choose");
        assertThat(row.getEntityId()).isEqualTo("abc");
        assertThat(row.getDetailsJson()).isEqualTo(Map.of("objective", "balanced"));
    }

    @Test
    void recordTelegramActionWithoutChatIdFallsBackToAnonymousApi() {
        service.recordTelegramAction(null, "calculate", "order", null, null);

        AuditLog row = savedRow();
        assertThat(row.getActorType()).isEqualTo("anonymous");
        assertThat(row.getSource()).isEqualTo("api");
        assertThat(row.getTelegramChatId()).isNull();
    }

    @Test
    void searchSubstitutesSentinelDateBoundsForNulls() {
        // The repository query has no `:param IS NULL` guard on the timestamps (PostgreSQL
        // cannot type bare timestamp parameters), so null dates must become sentinel bounds.
        when(repo.search(any(), any(), any(), any(), any(), any()))
            .thenReturn(org.springframework.data.domain.Page.empty());

        service.search(" admin ", "", null, null, null,
            org.springframework.data.domain.PageRequest.of(0, 20));

        ArgumentCaptor<java.time.Instant> from = ArgumentCaptor.forClass(java.time.Instant.class);
        ArgumentCaptor<java.time.Instant> to = ArgumentCaptor.forClass(java.time.Instant.class);
        verify(repo).search(org.mockito.ArgumentMatchers.eq("admin"),
            org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(),
            from.capture(), to.capture(), any());
        assertThat(from.getValue()).isEqualTo(java.time.Instant.EPOCH);
        assertThat(to.getValue()).isEqualTo(java.time.Instant.parse("9999-12-31T00:00:00Z"));
    }
}
