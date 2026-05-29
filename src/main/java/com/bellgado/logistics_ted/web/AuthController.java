package com.bellgado.logistics_ted.web;

import com.bellgado.logistics_ted.security.AppUserDetailsService.AuthenticatedUser;
import com.bellgado.logistics_ted.security.JwtService;
import com.bellgado.logistics_ted.security.JwtService.IssuedToken;
import com.bellgado.logistics_ted.web.dto.LoginRequest;
import com.bellgado.logistics_ted.web.dto.LoginResponse;
import com.bellgado.logistics_ted.web.dto.UserResponse;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
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

    private final AuthenticationManager authManager;
    private final JwtService jwtService;

    public AuthController(AuthenticationManager authManager, JwtService jwtService) {
        this.authManager = authManager;
        this.jwtService = jwtService;
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
            return ResponseEntity.status(401).body(Map.of("error", "Invalid credentials."));
        }

        AuthenticatedUser user = (AuthenticatedUser) auth.getPrincipal();
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
}
