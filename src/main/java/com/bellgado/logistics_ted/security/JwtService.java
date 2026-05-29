package com.bellgado.logistics_ted.security;

import com.bellgado.logistics_ted.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Date;
import java.util.Map;
import javax.crypto.SecretKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Issues and parses HS256 JWTs for /api/login. Stateless — no DB lookup, no revocation list.
 * Logout is client-side (drop the token); tokens remain valid until {@code exp}.
 */
@Service
@Slf4j
public class JwtService {

    private final JwtProperties props;
    private SecretKey key;

    public JwtService(JwtProperties props) {
        this.props = props;
    }

    @PostConstruct
    void init() {
        byte[] bytes = (props.getSecret() == null ? "" : props.getSecret()).getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            // HS256 requires >= 256 bits of key material. Pad short dev secrets with zeros so
            // local boots without TEDHOUSE_JWT_SECRET set still work — but warn loudly.
            log.warn("auth.jwt.secret is shorter than 32 bytes — padding for dev. Set TEDHOUSE_JWT_SECRET to a strong random value in production.");
            byte[] padded = new byte[32];
            System.arraycopy(bytes, 0, padded, 0, Math.min(bytes.length, 32));
            // If the secret was completely empty, seed with a deterministic placeholder so the
            // signature still varies between processes that override it later.
            if (bytes.length == 0) {
                Arrays.fill(padded, (byte) 0x5A);
            }
            bytes = padded;
        }
        this.key = Keys.hmacShaKeyFor(bytes);
    }

    /** Issue a token for an authenticated user. The role string is the lowercase DB label. */
    public IssuedToken issue(Integer userId, String username, String role) {
        Instant now = Instant.now();
        Instant exp = now.plus(props.getTtlMinutes(), ChronoUnit.MINUTES);
        String token = Jwts.builder()
            .issuer(props.getIssuer())
            .subject(username)
            .issuedAt(Date.from(now))
            .expiration(Date.from(exp))
            .claims(Map.of(
                "uid", userId,
                "role", role
            ))
            .signWith(key)
            .compact();
        return new IssuedToken(token, exp, props.getTtlMinutes() * 60L);
    }

    /** Parse + validate. Returns null if the token is malformed, expired, or signed with the wrong key. */
    public ParsedToken parse(String token) {
        try {
            Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
            Object uid = claims.get("uid");
            Integer userId = (uid instanceof Number n) ? n.intValue() : null;
            String role = claims.get("role", String.class);
            return new ParsedToken(userId, claims.getSubject(), role);
        } catch (JwtException | IllegalArgumentException ex) {
            return null;
        }
    }

    public record IssuedToken(String token, Instant expiresAt, long expiresInSeconds) {}

    public record ParsedToken(Integer userId, String username, String role) {}
}
