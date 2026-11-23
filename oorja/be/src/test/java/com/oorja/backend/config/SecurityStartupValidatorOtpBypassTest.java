package com.oorja.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A master OTP is a login backdoor, so it must fail closed anywhere except a developer machine or tests. */
class SecurityStartupValidatorOtpBypassTest {

    private SecurityStartupValidator validator(String bypassCode, String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        SecurityStartupValidator v = new SecurityStartupValidator();
        ReflectionTestUtils.setField(v, "jwtSecret", "a-strong-random-jwt-secret-with-more-than-32-chars");
        ReflectionTestUtils.setField(v, "adminPassword", "a-strong-admin-password-9917");
        ReflectionTestUtils.setField(v, "otpBypassCode", bypassCode);
        ReflectionTestUtils.setField(v, "environment", env);
        return v;
    }

    @Test
    void noBypassCodeIsAlwaysFine() {
        assertThatCode(() -> validator("").validate()).doesNotThrowAnyException();
        assertThatCode(() -> validator("", "prod").validate()).doesNotThrowAnyException();
    }

    @Test
    void bypassCodeWithNoProfileRefusesToStart() {
        // Render sets no SPRING_PROFILES_ACTIVE, so a block-list of "prod" would have let this through.
        assertThatThrownBy(() -> validator("123456").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OTP_BYPASS_CODE");
    }

    @Test
    void bypassCodeUnderProdOrDevRefusesToStart() {
        assertThatThrownBy(() -> validator("123456", "prod").validate()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> validator("123456", "dev").validate()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void bypassCodeAllowedUnderLocalAndTest() {
        assertThatCode(() -> validator("123456", "local").validate()).doesNotThrowAnyException();
        assertThatCode(() -> validator("123456", "test").validate()).doesNotThrowAnyException();
    }
}
