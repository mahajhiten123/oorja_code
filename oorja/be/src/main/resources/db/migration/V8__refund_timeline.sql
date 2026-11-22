-- =========================================================
-- V8: Track the individual dates in a refund's lifecycle so the customer
-- app can show a proper timeline (Cancellation -> Refund requested ->
-- Refund initiated -> Refund completed) instead of just a status badge.
-- =========================================================

ALTER TABLE refunds ADD COLUMN initiated_at TIMESTAMP NULL;
ALTER TABLE refunds ADD COLUMN completed_at TIMESTAMP NULL;

-- Backfill: for any refund that's already past PENDING, approximate the
-- missing timestamps from updated_at so existing rows still render sensibly.
UPDATE refunds SET initiated_at = updated_at WHERE status IN ('INITIATED', 'COMPLETED') AND initiated_at IS NULL;
UPDATE refunds SET completed_at = updated_at WHERE status = 'COMPLETED' AND completed_at IS NULL;
