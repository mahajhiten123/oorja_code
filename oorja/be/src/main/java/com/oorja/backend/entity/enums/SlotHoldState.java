package com.oorja.backend.entity.enums;

/**
 * Lifecycle of the slot seat reserved for a payment at checkout.
 * <p>
 * The seat is claimed when the Razorpay order is created — <i>before</i> the customer pays — so two
 * customers can never both pay for the last remaining seat. Without this, capacity was only checked
 * after money had been captured, at which point the booking could no longer be refused.
 *
 * <pre>
 * NONE      payment has no slot (quotation payments, or no draft slot supplied)
 * HELD      a seat in booking_slots is counted for this payment (expires at slot_hold_expires_at)
 * CONSUMED  the booking took over the seat — booking.slot_reserved is now the owner
 * RELEASED  the seat was given back (hold expired on an unpaid order)
 * </pre>
 *
 * Invariant (checked by scripts/slot_audit.sql):
 * {@code booking_slots.booked_count = #bookings(slot_reserved) + #payments(HELD)} for that slot,
 * ± the documented over-capacity fallback which is still counted.
 */
public enum SlotHoldState {
    NONE,
    HELD,
    CONSUMED,
    RELEASED
}
