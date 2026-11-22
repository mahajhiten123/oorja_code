package com.oorja.backend.service;

import com.oorja.backend.config.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    JwtService jwt;

    @BeforeEach
    void setUp() {
        jwt = new JwtService();
        ReflectionTestUtils.setField(jwt, "secret", "unit-test-secret-unit-test-secret-unit-test-secret-1234");
        ReflectionTestUtils.setField(jwt, "expirationMs", 60_000L);
    }

    @Test
    void tokenRoundTripsClaims() {
        String t = jwt.generateToken("a@x.com", 42L, "CUSTOMER");

        assertThat(jwt.extractEmail(t)).isEqualTo("a@x.com");
        assertThat(jwt.extractUserId(t)).isEqualTo(42L);
        assertThat(jwt.extractRole(t)).isEqualTo("CUSTOMER");
        assertThat(jwt.isTokenValid(t)).isTrue();
    }

    @Test
    void everyTokenHasUniqueJti() {
        assertThat(jwt.extractJti(jwt.generateToken("a", 1L, "CUSTOMER")))
                .isNotEqualTo(jwt.extractJti(jwt.generateToken("a", 1L, "CUSTOMER")));
    }

    @Test
    void expiredTokenIsInvalid() {
        ReflectionTestUtils.setField(jwt, "expirationMs", -1_000L);
        assertThat(jwt.isTokenValid(jwt.generateToken("a", 1L, "CUSTOMER"))).isFalse();
    }

    @Test
    void tamperedTokenIsInvalid() {
        String t = jwt.generateToken("a", 1L, "CUSTOMER");
        String tampered = t.substring(0, t.length() - 3) + (t.endsWith("AAA") ? "BBB" : "AAA");
        assertThat(jwt.isTokenValid(tampered)).isFalse();
        assertThatThrownBy(() -> jwt.extractEmail(tampered)).isInstanceOf(Exception.class);
    }

    @Test
    void tokenSignedWithAnotherSecretIsInvalid() {
        JwtService other = new JwtService();
        ReflectionTestUtils.setField(other, "secret", "another-secret-another-secret-another-secret-9999");
        ReflectionTestUtils.setField(other, "expirationMs", 60_000L);

        assertThat(jwt.isTokenValid(other.generateToken("a", 1L, "SUPER_ADMIN"))).isFalse();
    }

    @Test
    void garbageIsInvalid() {
        assertThat(jwt.isTokenValid("not-a-jwt")).isFalse();
    }
}
