package com.videoagent.service.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "test-secret-key-0123456789-0123456789-0123456789";

    private final JwtService jwtService = new JwtService(SECRET, 3600);

    @Test
    void generateAndParseToken() {
        String token = jwtService.generateToken(42L, "alice", "ROLE_USER");

        Claims claims = jwtService.parseToken(token);

        assertThat(claims.getSubject()).isEqualTo("alice");
        assertThat(((Number) claims.get("userId")).longValue()).isEqualTo(42L);
        assertThat(claims.get("role", String.class)).isEqualTo("ROLE_USER");
    }

    @Test
    void rejectTokenSignedWithDifferentSecret() {
        JwtService other = new JwtService("another-secret-key-0123456789-0123456789-0123456789", 3600);
        String token = other.generateToken(1L, "bob", "ROLE_USER");

        assertThatThrownBy(() -> jwtService.parseToken(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectGarbageToken() {
        assertThatThrownBy(() -> jwtService.parseToken("not-a-jwt"))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectExpiredToken() {
        JwtService shortLived = new JwtService(SECRET, -10);
        String token = shortLived.generateToken(1L, "carol", "ROLE_USER");

        assertThatThrownBy(() -> jwtService.parseToken(token))
                .isInstanceOf(JwtException.class);
    }
}
