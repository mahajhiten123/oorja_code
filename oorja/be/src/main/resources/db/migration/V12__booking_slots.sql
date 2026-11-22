-- =========================================================
-- V12: Slot/availability system.
--
-- Until now, "Preferred date" + "Preferred time slot" on the storefront was
-- purely decorative — any date/time the customer typed or clicked was
-- accepted with zero server-side check, so there was nothing stopping (say)
-- 200 customers from all landing on the same 9-11 AM slot on the same day.
--
-- One row per (slot_date, slot_time) tracks how many active bookings have
-- claimed that slot against a configurable capacity. Rows are created
-- on-demand (see SlotService) the first time a slot is touched, rather than
-- pre-populated — there's no meaningful upper bound on how far out a
-- customer might pick a date.
-- =========================================================

CREATE TABLE booking_slots (
    id            BIGSERIAL PRIMARY KEY,
    slot_date     DATE        NOT NULL,
    slot_time     TIME        NOT NULL,
    capacity      INT         NOT NULL,
    booked_count  INT         NOT NULL DEFAULT 0,
    created_at    TIMESTAMP   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP   NOT NULL DEFAULT now(),
    CONSTRAINT uq_booking_slots_date_time UNIQUE (slot_date, slot_time)
);

CREATE INDEX idx_booking_slots_date ON booking_slots(slot_date);
