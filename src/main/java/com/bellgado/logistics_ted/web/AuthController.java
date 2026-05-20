package com.bellgado.logistics_ted.web;

import com.bellgado.logistics_ted.security.AppUserDetailsService.AuthenticatedUser;
import com.bellgado.logistics_ted.web.dto.LoginRequest;
import com.bellgado.logistics_ted.web.dto.UserResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AuthController {

    private final AuthenticationManager authManager;
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    public AuthController(AuthenticationManager authManager) {
        this.authManager = authManager;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest body,
                                   HttpServletRequest request,
                                   jakarta.servlet.http.HttpServletResponse response) {
        if (body == null
                || body.username() == null || body.username().isBlank()
                || body.password() == null || body.password().isBlank()) {
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

        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);
        SecurityContextHolder.setContext(ctx);
        contextRepository.saveContext(ctx, request, response);

        AuthenticatedUser user = (AuthenticatedUser) auth.getPrincipal();
        return ResponseEntity.ok(new UserResponse(user.getUserId(), user.getUsername(), user.getRoleLabel()));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
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
}
