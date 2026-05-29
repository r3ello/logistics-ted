package com.bellgado.logistics_ted.security;

import com.bellgado.logistics_ted.security.AppUserDetailsService.AuthenticatedUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;

import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reads {@code Authorization: Bearer <jwt>} on every request, validates it, and seeds the
 * SecurityContext with an {@link AuthenticatedUser} principal. No DB hit per request — claims
 * are trusted because the signature was verified.
 *
 * <p>Invalid / missing tokens leave the context empty; {@link JsonAuthenticationEntryPoint}
 * then converts the eventual 401 into the Node-parity JSON shape. Explicitly permitAll
 * endpoints (/api/login, /api/telegram/webhook, static assets) are unaffected.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length()).trim();
            JwtService.ParsedToken parsed = jwtService.parse(token);
            if (parsed != null && parsed.username() != null && parsed.role() != null) {
                UsernamePasswordAuthenticationToken auth = getUsernamePasswordAuthenticationToken(parsed);
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContext ctx = SecurityContextHolder.createEmptyContext();
                ctx.setAuthentication(auth);
                SecurityContextHolder.setContext(ctx);
            }
        }
        chain.doFilter(request, response);
    }

    @NotNull
    private static UsernamePasswordAuthenticationToken getUsernamePasswordAuthenticationToken(JwtService.ParsedToken parsed) {
        String authority = "ROLE_" + parsed.role().toUpperCase();
        AuthenticatedUser principal = new AuthenticatedUser(
            parsed.userId(),
            parsed.username(),
            "",
            parsed.role(),
            List.of(new SimpleGrantedAuthority(authority))
        );
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());

    }
}
