package com.bellgado.logistics_ted.web.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.bellgado.logistics_ted.service.AuditLogService;
import com.bellgado.logistics_ted.web.audit.AuditActionResolver.ResolvedAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

/** The generic capture hook: what fires, what is skipped, and that failures are swallowed. */
class AuditLogInterceptorTest {

    /** Stand-in for a controller so a real {@link HandlerMethod} can be built. */
    static class DummyController {
        @SuppressWarnings("unused")
        public void handle() {}
    }

    private AuditLogService audit;
    private AuditLogInterceptor interceptor;
    private HandlerMethod handlerMethod;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() throws NoSuchMethodException {
        audit = mock(AuditLogService.class);
        interceptor = new AuditLogInterceptor(audit);
        handlerMethod = new HandlerMethod(new DummyController(),
            DummyController.class.getMethod("handle"));
        response = new MockHttpServletResponse();
        response.setStatus(200);
    }

    private static MockHttpServletRequest request(String method, String uri) {
        return new MockHttpServletRequest(method, uri);
    }

    @Test
    void recordsMutatingRequestWithStatus() {
        response.setStatus(201);
        interceptor.afterCompletion(request("POST", "/api/houses"), response, handlerMethod, null);

        ArgumentCaptor<ResolvedAction> resolved = ArgumentCaptor.forClass(ResolvedAction.class);
        verify(audit).recordHttpMutation(any(), eq("POST"), eq("/api/houses"), eq(201),
            resolved.capture(), any(), any());
        assertThat(resolved.getValue()).isEqualTo(new ResolvedAction("create", "house", null));
    }

    @Test
    void skipsReads() {
        interceptor.afterCompletion(request("GET", "/api/houses"), response, handlerMethod, null);
        verifyNoInteractions(audit);
    }

    @Test
    void skipsLoginLogoutAndWebhookPaths() {
        for (String path : new String[]{
                "/api/login", "/api/worker-login", "/api/logout", "/api/telegram/webhook"}) {
            interceptor.afterCompletion(request("POST", path), response, handlerMethod, null);
        }
        verifyNoInteractions(audit);
    }

    @Test
    void skipsNonControllerHandlers() {
        interceptor.afterCompletion(request("POST", "/api/houses"), response, new Object(), null);
        verifyNoInteractions(audit);
    }

    @Test
    void swallowsAuditFailures() {
        doThrow(new RuntimeException("db down")).when(audit)
            .recordHttpMutation(any(), anyString(), anyString(), anyInt(), any(), any(), any());
        assertThatCode(() -> interceptor.afterCompletion(
                request("DELETE", "/api/houses/12"), response, handlerMethod, null))
            .doesNotThrowAnyException();
    }
}
