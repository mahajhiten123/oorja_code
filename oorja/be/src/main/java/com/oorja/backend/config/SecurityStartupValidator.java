package com.oorja.backend.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Refuses to start the application if security-critical configuration is
 * missing or obviously weak, instead of silently falling back to an insecure
 * default. This is checked eagerly at startup (not lazily on first login) so
 * a misconfigured deploy fails loudly in CI/CD rather than shipping with a
 * guessable admin password or a forgeable JWT secret.
 *
 * Every one of these MUST be supplied via environment variable in every
 * environment - there is no built-in default for any of them.
 */
@Component
public class SecurityStartupValidator {

    private static final int MIN_SECRET_LENGTH = 32;
    private static final int MIN_PASSWORD_LENGTH = 12;

    /** Common weak/default values people accidentally leave in place - reject these outright even if the length check passes. */
    private static final Set<String> BLOCKED_VALUES = Set.of(
            "123456", "password", "admin", "admin123", "changeme", "changeit",
            "letmein", "welcome", "qwerty", "12345678",
            "change-this-super-secret-key-min-32-chars-long!!"
    );

    @Value("${app.jwt.secret:}")
    private String jwtSecret;

    @Value("${app.admin.password:}")
    private String adminPassword;

    @Value("${app.otp.bypass-code:}")
    private String otpBypassCode;

    @Autowired
    private Environment environment;

    @PostConstruct
    public void validate() {
        List<String> problems = new java.util.ArrayList<>();

        // A master OTP lets anyone log in as any customer or admin. Allow-list the profiles where it
        // may exist rather than block-list "prod": a deploy that forgets to set a profile must fail closed.
        if (otpBypassCode != null && !otpBypassCode.isBlank()
                && !environment.acceptsProfiles(Profiles.of("local", "test"))) {
            problems.add("app.otp.bypass-code (env: OTP_BYPASS_CODE) is set, but a bypass OTP is only allowed "
                    + "under the 'local' or 'test' Spring profile. Unset OTP_BYPASS_CODE.");
        }

        if (jwtSecret == null || jwtSecret.isBlank()) {
            problems.add("app.jwt.secret (env: JWT_SECRET) is not set.");
        } else if (jwtSecret.length() < MIN_SECRET_LENGTH) {
            problems.add("app.jwt.secret (env: JWT_SECRET) must be at least " + MIN_SECRET_LENGTH + " characters.");
        } else if (BLOCKED_VALUES.contains(jwtSecret.toLowerCase())) {
            problems.add("app.jwt.secret (env: JWT_SECRET) is a known placeholder value - generate a real random secret.");
        }

        if (adminPassword == null || adminPassword.isBlank()) {
            problems.add("app.admin.password (env: ADMIN_PASSWORD) is not set.");
        } else if (adminPassword.length() < MIN_PASSWORD_LENGTH) {
            problems.add("app.admin.password (env: ADMIN_PASSWORD) must be at least " + MIN_PASSWORD_LENGTH + " characters.");
        } else if (BLOCKED_VALUES.contains(adminPassword.toLowerCase())) {
            problems.add("app.admin.password (env: ADMIN_PASSWORD) is a common/default password - choose a strong, random one.");
        }

        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "Refusing to start: insecure or missing configuration.\n  - " +
                    String.join("\n  - ", problems) +
                    "\nSet these via environment variables (never commit real values) before starting the app.");
        }
    }
}
