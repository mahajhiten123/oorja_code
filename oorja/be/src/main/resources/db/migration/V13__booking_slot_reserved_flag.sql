-- =========================================================
-- V13: Tracks whether a booking actually holds a seat in booking_slots.
--
-- Not every booking participates in the slot grid — an AMC subscription
-- quote request (see BookingService.requestAmcQuote) stamps a placeholder
-- scheduled_date/scheduled_time before a real visit is ever arranged, and
-- that placeholder can coincidentally land on a real published slot time.
-- Without this flag, cancelling such a booking would incorrectly release a
-- seat in booking_slots that this booking never actually claimed, handing
-- another customer's slot an extra phantom seat.
-- =========================================================

ALTER TABLE bookings ADD COLUMN slot_reserved BOOLEAN NOT NULL DEFAULT FALSE;
