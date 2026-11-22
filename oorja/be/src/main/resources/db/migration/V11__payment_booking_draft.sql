-- =========================================================
-- V11: Booking-draft columns on payments.
--
-- Checkout already knows the plant/date/time/notes before the customer
-- even opens Razorpay Checkout. Storing them on the Payment row (not just
-- service+tier) means the webhook handler can create the Booking itself if
-- the customer's browser dies right after a successful payment and never
-- calls POST /api/customer/bookings — closing the "paid but no booking"
-- gap that Phase 1 explicitly left open.
-- =========================================================

ALTER TABLE payments ADD COLUMN draft_plant_id BIGINT REFERENCES plants(id);
ALTER TABLE payments ADD COLUMN draft_scheduled_date DATE;
ALTER TABLE payments ADD COLUMN draft_scheduled_time TIME;
ALTER TABLE payments ADD COLUMN draft_service_type VARCHAR(120);
ALTER TABLE payments ADD COLUMN draft_notes TEXT;
ALTER TABLE payments ADD COLUMN draft_location VARCHAR(255);
