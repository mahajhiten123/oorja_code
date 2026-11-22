-- Slot-counter audit. Must return ZERO rows on a healthy database.
--
-- booking_slots.booked_count is a denormalised counter kept in step by SlotService. It must always equal
--   (open bookings that own a seat on that slot)  +  (payments whose checkout hold is still HELD on that slot)
-- Any row returned is drift: either a bug, or a manual edit that bypassed the service.
-- Run it from cron / a monitor, and after any manual data fix.
WITH owned AS (
    SELECT scheduled_date AS d, scheduled_time AS t, count(*) AS n
      FROM bookings
     WHERE slot_reserved AND status <> 'CANCELLED'
     GROUP BY 1, 2
), held AS (
    SELECT draft_scheduled_date AS d, draft_scheduled_time AS t, count(*) AS n
      FROM payments
     WHERE slot_hold_state = 'HELD'
     GROUP BY 1, 2
)
SELECT s.slot_date, s.slot_time, s.capacity, s.booked_count,
       coalesce(o.n, 0) AS bookings_owning_seat, coalesce(h.n, 0) AS held_checkouts,
       s.booked_count - coalesce(o.n, 0) - coalesce(h.n, 0) AS drift,
       s.booked_count > s.capacity AS over_capacity
  FROM booking_slots s
  LEFT JOIN owned o ON o.d = s.slot_date AND o.t = s.slot_time
  LEFT JOIN held  h ON h.d = s.slot_date AND h.t = s.slot_time
 WHERE s.booked_count <> coalesce(o.n, 0) + coalesce(h.n, 0)
    OR s.booked_count > s.capacity
 ORDER BY s.slot_date, s.slot_time;
