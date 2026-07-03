package com.bellgado.logistics_ted.web;

import com.bellgado.logistics_ted.security.AppUserDetailsService.AuthenticatedUser;
import com.bellgado.logistics_ted.security.JwtService;
import com.bellgado.logistics_ted.security.JwtService.IssuedToken;
import com.bellgado.logistics_ted.service.AuditLogService;
import com.bellgado.logistics_ted.web.dto.LoginRequest;
import com.bellgado.logistics_ted.web.dto.LoginResponse;
import com.bellgado.logistics_ted.web.dto.UserResponse;
import com.bellgado.logistics_ted.web.logging.RequestCorrelationFilter;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthenticationManager authManager;
    private final JwtService jwtService;
    private final AuditLogService audit;

    public AuthController(AuthenticationManager authManager, JwtService jwtService,
                          AuditLogService audit) {
        this.authManager = authManager;
        this.jwtService = jwtService;
        this.audit = audit;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest body) {
        if (isNotValid(body)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Username and password required."));
        }

        Authentication auth;
        try {
            auth = authManager.authenticate(
                new UsernamePasswordAuthenticationToken(body.username().trim(), body.password())
            );
        } catch (BadCredentialsException | org.springframework.security.core.userdetails.UsernameNotFoundException ex) {
            auditLoginFailure("/api/login", body.username().trim(), "bad_credentials", 401);
            return ResponseEntity.status(401).body(Map.of("error", "Invalid credentials."));
        }

        AuthenticatedUser user = (AuthenticatedUser) auth.getPrincipal();
        auditLoginSuccess(AuditLogService.actorOf(user), "/api/login");
        IssuedToken issued = jwtService.issue(user.getUserId(), user.getUsername(), user.getRoleLabel());
        UserResponse userPayload = new UserResponse(user.getUserId(), user.getUsername(), user.getRoleLabel());
        return ResponseEntity.ok(LoginResponse.bearer(issued.token(), issued.expiresInSeconds(), userPayload));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout() {
        // Stateless tokens — client is expected to drop the token. We clear the in-flight context
        // defensively but there is no server-side revocation list.
        SecurityContextHolder.clearContext();
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof AuthenticatedUser user)) {
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        }
        return ResponseEntity.ok(new UserResponse(user.getUserId(), user.getUsername(), user.getRoleLabel()));
    }

    private boolean isNotValid(LoginRequest body) {
        return (body == null
                || StringUtils.isBlank(body.username())
                || StringUtils.isBlank(body.password()));
    }

    // Audit failures must never break the login flow — swallow and warn.
    private void auditLoginSuccess(AuditLogService.Actor actor, String endpoint) {
        try {
            audit.recordLoginSuccess(actor, endpoint,
                MDC.get(RequestCorrelationFilter.CLIENT_IP), MDC.get(RequestCorrelationFilter.REQUEST_ID));
        } catch (RuntimeException ex) {
            log.warn("audit: failed to record login success on {}", endpoint, ex);
        }
    }

    private void auditLoginFailure(String endpoint, String attemptedUsername, String reason, int status) {
        try {
            audit.recordLoginFailure(endpoint, attemptedUsername, reason, status,
                MDC.get(RequestCorrelationFilter.CLIENT_IP), MDC.get(RequestCorrelationFilter.REQUEST_ID));
        } catch (RuntimeException ex) {
            log.warn("audit: failed to record login failure on {}", endpoint, ex);
        }
    }
}
