-- =========================================================
-- V15: Attachments move to object storage + soft deletion
--
-- The DB never stores file bytes — only a storage_key that the configured
-- StorageProvider resolves (local disk in dev, S3 / Cloudflare R2 in prod).
-- Deletion becomes soft so an accidental delete of an important document is
-- recoverable; a nightly job destroys the bytes after the retention window.
-- =========================================================

-- storage_path -> storage_key. Existing local-disk paths ("bookings/42/uuid.jpg")
-- remain valid keys for the local provider, so nothing breaks on upgrade.
-- New uploads use the date-partitioned layout: bookings/2026/09/TKN-26-00231/uuid.jpg
ALTER TABLE booking_attachments RENAME COLUMN storage_path TO storage_key;

-- ---------- provenance (drives the deletion rules) ----------
ALTER TABLE booking_attachments
    ADD COLUMN uploaded_by_type  VARCHAR(16),
    ADD COLUMN uploaded_by       BIGINT,
    ADD COLUMN official_document BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN created_at        TIMESTAMP;

-- Everything uploaded before this migration came from the customer booking flow.
UPDATE booking_attachments ba
SET uploaded_by_type = 'CUSTOMER',
    uploaded_by      = b.customer_id,
    created_at       = COALESCE(ba.uploaded_at, now())
FROM bookings b
WHERE b.id = ba.booking_id;

-- Backstop for any orphan row the join missed.
UPDATE booking_attachments
SET uploaded_by_type = 'CUSTOMER',
    created_at       = COALESCE(uploaded_at, now())
WHERE uploaded_by_type IS NULL;

ALTER TABLE booking_attachments
    ALTER COLUMN uploaded_by_type SET NOT NULL,
    ALTER COLUMN created_at SET NOT NULL,
    ALTER COLUMN created_at SET DEFAULT now();

ALTER TABLE booking_attachments DROP COLUMN uploaded_at;

ALTER TABLE booking_attachments
    ADD CONSTRAINT chk_attachment_uploaded_by_type CHECK (uploaded_by_type IN ('ADMIN', 'CUSTOMER'));

-- ---------- soft delete + audit ----------
ALTER TABLE booking_attachments
    ADD COLUMN deleted_at      TIMESTAMP,
    ADD COLUMN deleted_by      BIGINT,
    ADD COLUMN deleted_by_type VARCHAR(16),
    ADD COLUMN delete_reason   VARCHAR(500),
    -- set once the nightly job has destroyed the object in storage
    ADD COLUMN purged_at       TIMESTAMP;

ALTER TABLE booking_attachments
    ADD CONSTRAINT chk_attachment_deleted_by_type CHECK (deleted_by_type IN ('ADMIN', 'CUSTOMER'));

-- Live attachments for a booking: the hot path for every booking response.
CREATE INDEX idx_booking_attachments_live
    ON booking_attachments (booking_id)
    WHERE deleted_at IS NULL;

-- Drives the nightly purge job.
CREATE INDEX idx_booking_attachments_purgeable
    ON booking_attachments (deleted_at)
    WHERE deleted_at IS NOT NULL AND purged_at IS NULL;
