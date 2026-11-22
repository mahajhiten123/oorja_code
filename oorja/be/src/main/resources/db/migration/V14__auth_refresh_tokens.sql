-- Refresh tokens + access-token denylist.
--
-- Access tokens (JWTs) are now short-lived (15 min by default) instead of 24h.
-- Long sessions are carried by a rotating, revocable refresh token stored here.
-- Only a SHA-256 hash of the refresh token is persisted, so a database leak
-- does not hand out usable session credentials.

CREATE TABLE refresh_tokens (
    id            BIGSERIAL PRIMARY KEY,
    token_hash    VARCHAR(128) NOT NULL UNIQUE,
    subject_id    BIGINT       NOT NULL,
    subject_type  VARCHAR(16)  NOT NULL,
    email         VARCHAR(255) NOT NULL,
    role          VARCHAR(32)  NOT NULL,
    expires_at    TIMESTAMP    NOT NULL,
    revoked_at    TIMESTAMP,
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_refresh_subject_type CHECK (subject_type IN ('ADMIN', 'CUSTOMER'))
);

-- Primary lookup path: hash presented by the client on /refresh.
CREATE INDEX idx_refresh_tokens_hash ON refresh_tokens (token_hash);

-- Used by "revoke every live session for this subject" (reuse detection, deactivation).
CREATE INDEX idx_refresh_tokens_subject ON refresh_tokens (subject_id, subject_type);

-- Used by the scheduled cleanup job.
CREATE INDEX idx_refresh_tokens_expires_at ON refresh_tokens (expires_at);


-- Denylist of access tokens revoked before their natural expiry (logout).
-- Keyed by the JWT's jti claim; rows are purged once expires_at has passed,
-- since after that the token fails its own expiry check anyway.
CREATE TABLE revoked_access_tokens (
    jti         VARCHAR(64) PRIMARY KEY,
    expires_at  TIMESTAMP   NOT NULL,
    revoked_at  TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_revoked_access_tokens_expires_at ON revoked_access_tokens (expires_at);
