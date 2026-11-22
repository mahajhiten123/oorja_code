-- =========================================================
-- V3: Phone OTP + Google OAuth support, richer booking fields
-- =========================================================

-- otp_tokens.email was really a generic "identifier" (email OR phone).
-- Rename it and record which channel (EMAIL / SMS) the code went out on.
ALTER TABLE otp_tokens RENAME COLUMN email TO identifier;
ALTER TABLE otp_tokens ADD COLUMN channel VARCHAR(10) NOT NULL DEFAULT 'EMAIL';

DROP INDEX IF EXISTS idx_otp_email_purpose;
CREATE INDEX idx_otp_identifier_purpose ON otp_tokens(identifier, purpose);

-- Customers who sign in with Google are linked by their Google "sub" id,
-- so a later login (even if they change their name on Google) still
-- resolves to the same account. Nullable/unique: only Google users have it.
ALTER TABLE customers ADD COLUMN google_id VARCHAR(64) UNIQUE;

-- Bookings made from the storefront (as opposed to the admin console) carry
-- a few extra display/payment fields that the original schema didn't need.
ALTER TABLE bookings ADD COLUMN location TEXT;
ALTER TABLE bookings ADD COLUMN amount_paise BIGINT;
ALTER TABLE bookings ADD COLUMN payment_ref VARCHAR(100);
ALTER TABLE bookings ADD COLUMN service_label VARCHAR(200);
