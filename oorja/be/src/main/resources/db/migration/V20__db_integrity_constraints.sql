-- =========================================================
-- V20: DB-level integrity backstops (Phase 1 hardening)
--
-- Every constraint here backs up a rule the Java code already tries to
-- enforce. Application checks (existsBy..., row locks) stay; these make the
-- rule true even when two requests race past them.
--
-- BEFORE RUNNING ON AN EXISTING DATABASE, run the PRE-FLIGHT queries below.
-- Each must return zero rows, otherwise the matching CREATE UNIQUE INDEX /
-- ADD CONSTRAINT will fail and abort the migration.
-- =========================================================

-- ---------------- PRE-FLIGHT (run manually, not part of migration) ----------------
-- SELECT lower(email), count(*) FROM customers GROUP BY 1 HAVING count(*) > 1;
-- SELECT lower(email), count(*) FROM admins    GROUP BY 1 HAVING count(*) > 1;
-- SELECT razorpay_payment_id, count(*) FROM payments
--   WHERE razorpay_payment_id IS NOT NULL GROUP BY 1 HAVING count(*) > 1;
-- SELECT booking_id, count(*) FROM payments
--   WHERE booking_id IS NOT NULL AND quotation_id IS NULL GROUP BY 1 HAVING count(*) > 1;
-- SELECT payment_ref, count(*) FROM bookings
--   WHERE payment_ref IS NOT NULL GROUP BY 1 HAVING count(*) > 1;
-- SELECT razorpay_refund_id, count(*) FROM refunds
--   WHERE razorpay_refund_id IS NOT NULL GROUP BY 1 HAVING count(*) > 1;
-- SELECT DISTINCT status FROM bookings;      -- must be within the enum values below
-- SELECT DISTINCT status FROM payments;
-- SELECT DISTINCT status FROM refunds;
-- SELECT DISTINCT status FROM quotations;
-- SELECT DISTINCT status FROM customers;
-- SELECT DISTINCT availability FROM technicians;
-- SELECT count(*) FROM booking_slots WHERE booked_count < 0 OR capacity <= 0;
-- SELECT count(*) FROM payments WHERE amount_paise <= 0;
-- ----------------------------------------------------------------------------------

-- ---------- Identity: case-insensitive email uniqueness ----------
-- The existing UNIQUE(email) is case-sensitive, and the Google sign-in path
-- stores identity.email() without lower-casing, so 'Rahul@x.com' and
-- 'rahul@x.com' could both exist. Application code looks users up with
-- findByEmailIgnoreCase, so the DB must agree.
CREATE UNIQUE INDEX uq_customers_email_lower ON customers (lower(email));
CREATE UNIQUE INDEX uq_admins_email_lower    ON admins (lower(email));

-- Phone uniqueness is a PRODUCT decision (shared family/office numbers?).
-- Uncomment only if one phone == one account is the rule:
-- CREATE UNIQUE INDEX uq_customers_phone ON customers (phone) WHERE phone IS NOT NULL;

-- ---------- Payments: gateway identifiers ----------
-- razorpay_order_id is already UNIQUE (V9). razorpay_payment_id was not.
CREATE UNIQUE INDEX uq_payments_razorpay_payment_id
    ON payments (razorpay_payment_id) WHERE razorpay_payment_id IS NOT NULL;

-- One captured payment may fund at most one booking. consumeCapturedPayment()
-- guards this with a row lock; this is the backstop if any path forgets the lock.
-- Quotation payments are excluded: they attach to an existing booking.
CREATE UNIQUE INDEX uq_payments_booking_once
    ON payments (booking_id) WHERE booking_id IS NOT NULL AND quotation_id IS NULL;

-- The reverse direction: one Razorpay payment id on at most one booking.
CREATE UNIQUE INDEX uq_bookings_payment_ref
    ON bookings (payment_ref) WHERE payment_ref IS NOT NULL;

-- ---------- Refunds: gateway identifier ----------
CREATE UNIQUE INDEX uq_refunds_razorpay_refund_id
    ON refunds (razorpay_refund_id) WHERE razorpay_refund_id IS NOT NULL;

-- ---------- Enum drift guards ----------
-- V19 existed because 'BOOKED' was written to a column whose Java enum had
-- no such value. These CHECKs make the DB reject it at write time instead of
-- the app 500ing on read. KEEP IN SYNC with the enums when adding values
-- (new enum constant => new migration that widens the CHECK).
ALTER TABLE bookings ADD CONSTRAINT ck_bookings_status
    CHECK (status IN ('PENDING','ASSIGNED','ON_SITE','COMPLETED','CANCELLED'));

ALTER TABLE payments ADD CONSTRAINT ck_payments_status
    CHECK (status IN ('CREATED','PENDING','CAPTURED','FAILED','CANCELLED','REFUNDED','PARTIALLY_REFUNDED'));

ALTER TABLE refunds ADD CONSTRAINT ck_refunds_status
    CHECK (status IN ('REQUESTED','UNDER_REVIEW','APPROVED','PROCESSING','COMPLETED','REJECTED','FAILED','CANCELLED'));

ALTER TABLE quotations ADD CONSTRAINT ck_quotations_status
    CHECK (status IN ('DRAFT','SENT','VIEWED','ACCEPTED','REJECTED','EXPIRED'));

ALTER TABLE customers ADD CONSTRAINT ck_customers_status
    CHECK (status IN ('ACTIVE','INACTIVE'));

ALTER TABLE technicians ADD CONSTRAINT ck_technicians_availability
    CHECK (availability IN ('AVAILABLE','OFF_DUTY'));

-- ---------- Money / counter sanity ----------
ALTER TABLE payments ADD CONSTRAINT ck_payments_amount_positive CHECK (amount_paise > 0);
ALTER TABLE bookings ADD CONSTRAINT ck_bookings_amount_non_negative
    CHECK (amount_paise IS NULL OR amount_paise >= 0);

-- NOTE: deliberately NO "booked_count <= capacity" check. SlotService's
-- reserveBestEffort() intentionally pushes a slot over capacity when money
-- is already captured. Capacity for normal customers is enforced by the
-- atomic tryClaimSeat() UPDATE ... WHERE booked_count < capacity.
-- The lower bound catches a double-release bug instead.
ALTER TABLE booking_slots ADD CONSTRAINT ck_booking_slots_counts
    CHECK (booked_count >= 0 AND capacity > 0);

-- ---------- Request idempotency ----------
-- Clients send an Idempotency-Key header (one fresh UUID per user intent, reused
-- on every retry of that intent). Scoped per customer so one customer's key can
-- never collide with, or reveal, another's. NULL = caller didn't send one.
ALTER TABLE bookings ADD COLUMN idempotency_key VARCHAR(80);
ALTER TABLE payments ADD COLUMN idempotency_key VARCHAR(80);

CREATE UNIQUE INDEX uq_bookings_customer_idem
    ON bookings (customer_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE UNIQUE INDEX uq_payments_customer_idem
    ON payments (customer_id, idempotency_key) WHERE idempotency_key IS NOT NULL;

-- ---------- Reference-number generation (replaces max(id)+1 in CodeGeneratorService) ----------
-- Each sequence starts after the highest number already issued (parsed from the
-- trailing digits of the existing code), so it can never collide with old rows,
-- including seed rows such as TKN-26-00231 whose number exceeds their id.
CREATE SEQUENCE seq_customer_code;
CREATE SEQUENCE seq_plant_code;
CREATE SEQUENCE seq_technician_code;
CREATE SEQUENCE seq_booking_token;
CREATE SEQUENCE seq_quotation_number;
CREATE SEQUENCE seq_refund_number;
CREATE SEQUENCE seq_service_report_number;
CREATE SEQUENCE seq_ticket_number;
CREATE SEQUENCE seq_payment_number;

-- customers: codes are CUS + (1000 + n), first new code is CUS1001 at minimum.
SELECT setval('seq_customer_code',
    GREATEST(COALESCE((SELECT max(substring(customer_code from '[0-9]+$')::bigint) FROM customers), 0) + 1, 1001), false);
SELECT setval('seq_plant_code',
    GREATEST(COALESCE((SELECT max(substring(plant_code from '[0-9]+$')::bigint) FROM plants), 0) + 1, 1), false);
SELECT setval('seq_technician_code',
    GREATEST(COALESCE((SELECT max(substring(technician_code from '[0-9]+$')::bigint) FROM technicians), 0) + 1, 1), false);
SELECT setval('seq_booking_token',
    GREATEST(COALESCE((SELECT max(substring(token from '[0-9]+$')::bigint) FROM bookings), 0) + 1, 1), false);
SELECT setval('seq_quotation_number',
    GREATEST(COALESCE((SELECT max(substring(quotation_number from '[0-9]+$')::bigint) FROM quotations), 0) + 1, 1), false);
SELECT setval('seq_refund_number',
    GREATEST(COALESCE((SELECT max(substring(refund_number from '[0-9]+$')::bigint) FROM refunds), 0) + 1, 1), false);
SELECT setval('seq_service_report_number',
    GREATEST(COALESCE((SELECT max(substring(report_number from '[0-9]+$')::bigint) FROM service_reports), 0) + 1, 1), false);
SELECT setval('seq_ticket_number',
    GREATEST(COALESCE((SELECT max(substring(ticket_number from '[0-9]+$')::bigint) FROM support_tickets), 0) + 1, 1), false);
SELECT setval('seq_payment_number',
    GREATEST(COALESCE((SELECT max(substring(payment_number from '[0-9]+$')::bigint) FROM payments), 0) + 1, 1), false);

-- Invoices and credit notes: gapless, so a counter row instead of a sequence.
CREATE TABLE document_counters (
    name        VARCHAR(40) PRIMARY KEY,
    next_value  BIGINT      NOT NULL CHECK (next_value >= 1)
);
INSERT INTO document_counters (name, next_value)
SELECT 'invoice',
       GREATEST(COALESCE((SELECT max(substring(invoice_number from '[0-9]+$')::bigint) FROM invoices), 0) + 1, 1);
