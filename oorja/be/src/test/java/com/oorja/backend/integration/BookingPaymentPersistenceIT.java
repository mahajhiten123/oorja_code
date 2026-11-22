package com.oorja.backend.integration;

import com.oorja.backend.support.AbstractIT;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real PostgreSQL, real Flyway schema, real transactions: proves the money/seat bookkeeping is what the database
 * actually holds, and that the database itself (not just application code) refuses impossible states.
 */
class BookingPaymentPersistenceIT extends AbstractIT {

    @Test
    void paidBookingPersistsLinkedPaymentBookingAndSeat() throws Exception {
        TestCustomer c = newCustomer();
        LocalDate day = freshDay();

        PaidBooking pb = paidBooking(c, day, NINE);

        assertThat(count("select count(*) from bookings where id=? and status='PENDING' and slot_reserved=true and payment_ref=?",
                pb.bookingId(), pb.paid().paymentId())).isEqualTo(1);
        assertThat(count("select count(*) from payments where razorpay_order_id=? and status='CAPTURED' and booking_id=?",
                pb.paid().orderId(), pb.bookingId())).isEqualTo(1);
        assertThat(booked(day, NINE)).isEqualTo(1);
        // amount on the booking comes from the captured payment row
        assertThat(count("select count(*) from bookings b join payments p on p.booking_id=b.id "
                + "where b.id=? and b.amount_paise=p.amount_paise", pb.bookingId())).isEqualTo(1);
    }

    @Test
    void cancelReleasesSeatAndCreatesExactlyOneRefund() throws Exception {
        TestCustomer c = newCustomer();
        LocalDate day = freshDay();
        PaidBooking pb = paidBooking(c, day, NINE);

        statusOk(doPost("/api/customer/bookings/" + pb.bookingId() + "/cancel", tokenFor(c), "{}"));

        assertThat(count("select count(*) from bookings where id=? and status='CANCELLED' and slot_reserved=false", pb.bookingId()))
                .isEqualTo(1);
        assertThat(booked(day, NINE)).isZero();
        assertThat(count("select count(*) from refunds where booking_id=?", pb.bookingId())).isEqualTo(1);
        assertThat(count("select count(*) from refund_events e join refunds r on r.id=e.refund_id where r.booking_id=?",
                pb.bookingId())).isGreaterThanOrEqualTo(1);
    }

    @Test
    void cancellingTwiceNeverCreatesASecondRefundOrReleasesTheSeatTwice() throws Exception {
        TestCustomer c = newCustomer();
        LocalDate day = freshDay();
        PaidBooking pb = paidBooking(c, day, NINE);
        TestCustomer other = newCustomer();
        // another customer holds a seat in the same slot: a double release would steal it
        paidBooking(other, day, NINE);

        statusOk(doPost("/api/customer/bookings/" + pb.bookingId() + "/cancel", tokenFor(c), "{}"));
        statusOk(doPost("/api/customer/bookings/" + pb.bookingId() + "/cancel", tokenFor(c), "{}"));

        assertThat(count("select count(*) from refunds where booking_id=?", pb.bookingId())).isEqualTo(1);
        assertThat(booked(day, NINE)).isEqualTo(1);
    }

    @Test
    void rescheduleMovesTheSeatBetweenSlots() throws Exception {
        TestCustomer c = newCustomer();
        LocalDate day = freshDay();
        LocalDate newDay = freshDay();
        PaidBooking pb = paidBooking(c, day, NINE);

        statusOk(doPost("/api/customer/bookings/" + pb.bookingId() + "/reschedule", tokenFor(c),
                "{\"scheduledDate\":\"" + newDay + "\",\"scheduledTime\":\"11:00:00\"}"));

        assertThat(booked(day, NINE)).isZero();
        assertThat(booked(newDay, ELEVEN)).isEqualTo(1);
        assertThat(count("select count(*) from bookings where id=? and scheduled_date=?", pb.bookingId(), java.sql.Date.valueOf(newDay)))
                .isEqualTo(1);
    }

    @Test
    void databaseRejectsInvalidBookingStatus() throws Exception {
        TestCustomer c = newCustomer();
        PaidBooking pb = paidBooking(c, freshDay(), NINE);

        assertThatThrownBy(() -> jdbc.update("update bookings set status='BOGUS' where id=?", pb.bookingId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsReusingOnePaymentIdForTwoPayments() throws Exception {
        TestCustomer c = newCustomer();
        PaidBooking pb = paidBooking(c, freshDay(), NINE);
        TestCustomer d = newCustomer();
        Paid second = pay(d, "cleaning", freshDay(), NINE);

        assertThatThrownBy(() -> jdbc.update("update payments set razorpay_payment_id=? where razorpay_order_id=?",
                pb.paid().paymentId(), second.orderId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsSecondBookingForTheSamePaymentRef() throws Exception {
        TestCustomer c = newCustomer();
        PaidBooking pb = paidBooking(c, freshDay(), NINE);
        TestCustomer d = newCustomer();
        PaidBooking other = paidBooking(d, freshDay(), NINE);

        assertThatThrownBy(() -> jdbc.update("update bookings set payment_ref=? where id=?",
                pb.paid().paymentId(), other.bookingId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsCustomerEmailDifferingOnlyByCase() {
        TestCustomer c = newCustomer();

        assertThatThrownBy(() -> jdbc.update("insert into customers(customer_code,name,email) values (?,?,?)",
                "C" + shortId(), "Dup", c.email().toUpperCase()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsNegativeSlotCounter() throws Exception {
        TestCustomer c = newCustomer();
        LocalDate day = freshDay();
        paidBooking(c, day, NINE);

        assertThatThrownBy(() -> jdbc.update("update booking_slots set booked_count=-1 where slot_date=?", java.sql.Date.valueOf(day)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rebookFromRefundRevivesBookingWithNewSeatAndWithdrawsRefund() throws Exception {
        TestCustomer c = newCustomer();
        LocalDate day = freshDay();
        LocalDate newDay = freshDay();
        PaidBooking pb = paidBooking(c, day, NINE);
        // Installation-sized payments land in the manual-review queue, so the refund is still withdrawable.
        statusOk(doPost("/api/customer/bookings/" + pb.bookingId() + "/cancel", tokenFor(c), "{}"));
        Long refundId = jdbc.queryForObject("select id from refunds where booking_id=?", Long.class, pb.bookingId());
        String status = jdbc.queryForObject("select status from refunds where id=?", String.class, refundId);

        if (status.equals("REQUESTED") || status.equals("UNDER_REVIEW")) {
            statusOk(doPost("/api/customer/refunds/" + refundId + "/rebook", tokenFor(c),
                    "{\"scheduledDate\":\"" + newDay + "\",\"scheduledTime\":\"13:00:00\"}"));
            assertThat(count("select count(*) from bookings where id=? and status='PENDING' and slot_reserved=true", pb.bookingId()))
                    .isEqualTo(1);
            assertThat(booked(newDay, THIRTEEN)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select status from refunds where id=?", String.class, refundId)).isEqualTo("CANCELLED");
        } else {
            // Auto-approved refunds go straight to the gateway; rebooking must then be refused and change nothing.
            assertThat(statusOf(doPost("/api/customer/refunds/" + refundId + "/rebook", tokenFor(c),
                    "{\"scheduledDate\":\"" + newDay + "\",\"scheduledTime\":\"13:00:00\"}"))).isGreaterThanOrEqualTo(400);
            assertThat(booked(newDay, THIRTEEN)).isZero();
        }
    }

    private void statusOk(org.springframework.test.web.servlet.ResultActions r) throws Exception {
        r.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    }
}
