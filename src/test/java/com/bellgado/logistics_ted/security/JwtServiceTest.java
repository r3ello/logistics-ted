package com.bellgado.logistics_ted.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.bellgado.logistics_ted.config.JwtProperties;
import org.junit.jupiter.api.Test;

/**
 * Pure unit test — no Spring context, no Postgres. Verifies that JwtService can issue and parse
 * a token round-trip, that the role/uid/username claims survive, and that a tampered token
 * (or one signed with a different secret) is rejected rather than returning bogus claims.
 */
class JwtServiceTest {

    private JwtService serviceWithSecret(String secret) {
        JwtProperties props = new JwtProperties();
        props.setSecret(secret);
        props.setTtlMinutes(60);
        JwtService svc = new JwtService(props);
        svc.init();
        return svc;
    }

    @Test
    void issuedTokenRoundTripsThroughParse() {
        JwtService svc = serviceWithSecret("test-secret-padded-to-32-bytes-min-len!");
        JwtService.IssuedToken issued = svc.issue(42, "admin", "admin");

        assertThat(issued.token()).isNotBlank();
        assertThat(issued.expiresInSeconds()).isEqualTo(60 * 60L);

        JwtService.ParsedToken parsed = svc.parse(issued.token());
        assertThat(parsed).isNotNull();
        assertThat(parsed.userId()).isEqualTo(42);
        assertThat(parsed.username()).isEqualTo("admin");
        assertThat(parsed.role()).isEqualTo("admin");
    }

    @Test
    void parseReturnsNullForGarbage() {
        JwtService svc = serviceWithSecret("test-secret-padded-to-32-bytes-min-len!");
        assertThat(svc.parse("not-a-jwt")).isNull();
        assertThat(svc.parse("")).isNull();
    }

    @Test
    void tokenSignedWithDifferentSecretIsRejected() {
        JwtService alice = serviceWithSecret("alice-secret-padded-to-32-bytes-min!");
        JwtService bob   = serviceWithSecret("bob-secret-padded-to-32-bytes-min-len");

        String aliceToken = alice.issue(1, "user", "user").token();

        // Bob has a different key — Alice's signature must not validate.
        assertThat(bob.parse(aliceToken)).isNull();
        // Sanity: Alice can still parse her own token.
        assertThat(alice.parse(aliceToken)).isNotNull();
    }
}
