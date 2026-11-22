-- =========================================================
-- V21: Concurrency hardening (Phase 1, part 2)
--
-- 1. Slot holds. A seat in booking_slots is now claimed when the Razorpay order is
--    created, not after the money has been captured. Before this, the capacity check
--    only ran for UNPAID bookings; every paid booking used reserveBestEffort(), which
--    never refuses - so two customers could both pay for the last seat and both get a
--    booking (5/4 on a capacity-4 slot).
-- 2. One open (unpaid) order per quotation, so a double-click / second tab can't open two
--    Razorpay orders for the same quotation and charge the customer twice.
-- 3. Indexes on foreign keys that had none (every ON DELETE / join on them was a seq scan).
-- 4. Webhook-event status guard and a payments sanity check.
--
-- PRE-FLIGHT (run manually on an existing database; each must return zero rows):
--   SELECT quotation_id, count(*) FROM payments
--     WHERE quotation_id IS NOT NULL AND status IN ('CREATED','PENDING') GROUP BY 1 HAVING count(*) > 1;
--   SELECT DISTINCT status FROM webhook_events;   -- must be within RECEIVED/PROCESSED/IGNORED/FAILED
--   If the first query returns rows, cancel the older duplicate orders first:
--   UPDATE payments SET status='CANCELLED' WHERE id IN (<older duplicate ids>);
-- =========================================================

-- ---------- 1. Slot holds ----------
ALTER TABLE payments ADD COLUMN slot_hold_state      VARCHAR(16) NOT NULL DEFAULT 'NONE';
ALTER TABLE payments ADD COLUMN slot_hold_expires_at TIMESTAMP;

ALTER TABLE payments ADD CONSTRAINT ck_payments_slot_hold_state
    CHECK (slot_hold_state IN ('NONE','HELD','CONSUMED','RELEASED'));

-- A HELD seat must say which slot it is on and when it lapses, otherwise it could never be released.
ALTER TABLE payments ADD CONSTRAINT ck_payments_slot_hold_complete
    CHECK (slot_hold_state <> 'HELD'
           OR (draft_scheduled_date IS NOT NULL
               AND draft_scheduled_time IS NOT NULL
               AND slot_hold_expires_at IS NOT NULL));

-- The expiry job's scan: only HELD rows, ordered by expiry.
CREATE INDEX idx_payments_expired_holds ON payments (slot_hold_expires_at) WHERE slot_hold_state = 'HELD';

-- ---------- 2. One open order per quotation ----------
CREATE UNIQUE INDEX uq_payments_quotation_open
    ON payments (quotation_id)
    WHERE quotation_id IS NOT NULL AND status IN ('CREATED','PENDING');

-- ---------- 3. Missing foreign-key indexes ----------
CREATE INDEX idx_payments_quotation               ON payments (quotation_id)             WHERE quotation_id IS NOT NULL;
CREATE INDEX idx_payments_draft_plant             ON payments (draft_plant_id)           WHERE draft_plant_id IS NOT NULL;
CREATE INDEX idx_invoices_quotation               ON invoices (quotation_id)             WHERE quotation_id IS NOT NULL;
CREATE INDEX idx_invoices_original                ON invoices (original_invoice_id)      WHERE original_invoice_id IS NOT NULL;
CREATE INDEX idx_quotations_accepted_version      ON quotations (accepted_version_id)    WHERE accepted_version_id IS NOT NULL;
CREATE INDEX idx_qrr_customer                     ON quotation_revision_requests (customer_id);
CREATE INDEX idx_qrr_version                      ON quotation_revision_requests (quotation_version_id);
CREATE INDEX idx_service_reports_technician       ON service_reports (technician_id)     WHERE technician_id IS NOT NULL;
CREATE INDEX idx_support_tickets_booking          ON support_tickets (booking_id)        WHERE booking_id IS NOT NULL;
CREATE INDEX idx_support_tickets_invoice          ON support_tickets (invoice_id)        WHERE invoice_id IS NOT NULL;
CREATE INDEX idx_support_tickets_refund           ON support_tickets (refund_id)         WHERE refund_id IS NOT NULL;

-- ---------- 4. Guards ----------
ALTER TABLE webhook_events ADD CONSTRAINT ck_webhook_events_status
    CHECK (status IN ('RECEIVED','PROCESSED','IGNORED','FAILED'));

-- Money that was captured must carry the gateway's payment id. NOT VALID: enforced for every new
-- write, without scanning/failing on any legacy row (run VALIDATE CONSTRAINT after cleaning, if any).
ALTER TABLE payments ADD CONSTRAINT ck_payments_captured_has_gateway_id
    CHECK (status NOT IN ('CAPTURED','PARTIALLY_REFUNDED','REFUNDED') OR razorpay_payment_id IS NOT NULL)
    NOT VALID;
