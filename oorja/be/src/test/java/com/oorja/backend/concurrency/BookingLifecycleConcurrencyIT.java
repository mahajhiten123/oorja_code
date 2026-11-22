package com.oorja.backend.concurrency;

import com.oorja.backend.support.AbstractIT;
import com.oorja.backend.support.Parallel;
import com.oorja.backend.support.Parallel.Outcome;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Cancellation, rescheduling and refunds racing each other on the same booking. */
class BookingLifecycleConcurrencyIT extends AbstractIT {

    @Test
    void concurrentCancelAndRescheduleLeaveConsistentSeatsAndAtMostOneRefund() throws Exception {
        for (int round = 0; round < 3; round++) {
            TestCustomer c = newCustomer();
            LocalDate day = freshDay();
            LocalDate newDay = freshDay();
            PaidBooking pb = paidBooking(c, day, NINE);

            List<Outcome<Integer>> results = Parallel.run(2, i -> i == 0
                    ? statusOf(doPost("/api/customer/bookings/" + pb.bookingId() + "/cancel", tokenFor(c), "{}"))
                    : statusOf(doPost("/api/customer/bookings/" + pb.bookingId() + "/reschedule", tokenFor(c),
                            "{\"scheduledDate\":\"" + newDay + "\",\"scheduledTime\":\"11:00:00\"}")));

            assertThat(results).noneMatch(Outcome::failed);
            assertThat(results).allMatch(o -> o.value() == 200 || o.value() == 409);

            String status = jdbc.queryForObject("select status from bookings where id=?", String.class, pb.bookingId());
            int oldSeat = booked(day, NINE);
            int newSeat = booked(newDay, ELEVEN);
            long refunds = count("select count(*) from refunds where booking_id=?", pb.bookingId());

            if (status.equals("CANCELLED")) {
                // whichever order they ran in, a cancelled booking holds no seat anywhere, and the money is owed once
                assertThat(oldSeat + newSeat).as("no seat leaked after cancel").isZero();
                assertThat(refunds).isEqualTo(1);
            } else {
                // reschedule won and cancel lost (cancel can only lose by 409): exactly one seat, at the new slot
                assertThat(status).isEqualTo("PENDING");
                assertThat(oldSeat).isZero();
                assertThat(newSeat).isEqualTo(1);
                assertThat(refunds).isZero();
            }
        }
    }

    @Test
    void manyConcurrentCancelsOfOneBookingRaiseExactlyOneRefund() throws Exception {
        TestCustomer c = newCustomer();
        LocalDate day = freshDay();
        PaidBooking pb = paidBooking(c, day, NINE);
        TestCustomer neighbour = newCustomer();
        paidBooking(neighbour, day, NINE); // shares the slot: a double release would show up here

        List<Outcome<Integer>> results = Parallel.run(20, i ->
                statusOf(doPost("/api/customer/bookings/" + pb.bookingId() + "/cancel", tokenFor(c), "{}")));

        assertThat(results).noneMatch(Outcome::failed);
        assertThat(results).allMatch(o -> o.value() == 200);
        assertThat(count("select count(*) from refunds where booking_id=?", pb.bookingId())).isEqualTo(1);
        assertThat(booked(day, NINE)).isEqualTo(1);
    }

    @Test
    void manyConcurrentReschedulesKeepExactlyOneSeatForTheBooking() throws Exception {
        TestCustomer c = newCustomer();
        LocalDate day = freshDay();
        PaidBooking pb = paidBooking(c, day, NINE);
        List<LocalDate> targets = List.of(freshDay(), freshDay(), freshDay(), freshDay(), freshDay());

        List<Outcome<Integer>> results = Parallel.run(10, i -> statusOf(doPost(
                "/api/customer/bookings/" + pb.bookingId() + "/reschedule", tokenFor(c),
                "{\"scheduledDate\":\"" + targets.get(i % targets.size()) + "\",\"scheduledTime\":\"13:00:00\"}")));

        assertThat(results).noneMatch(Outcome::failed);
        assertThat(results).allMatch(o -> o.value() == 200);
        long totalSeats = booked(day, NINE);
        for (LocalDate t : targets) totalSeats += booked(t, THIRTEEN);
        assertThat(totalSeats).as("the booking holds exactly one seat in total").isEqualTo(1);
        LocalDate finalDay = jdbc.queryForObject("select scheduled_date from bookings where id=?", java.sql.Date.class, pb.bookingId()).toLocalDate();
        assertThat(booked(finalDay, THIRTEEN)).isEqualTo(1);
    }

    @Test
    void cancelRacingAdminCompleteEndsInOneConsistentState() throws Exception {
        TestCustomer c = newCustomer();
        LocalDate day = freshDay();
        PaidBooking pb = paidBooking(c, day, NINE);

        List<Outcome<Integer>> results = Parallel.run(2, i -> i == 0
                ? statusOf(doPost("/api/customer/bookings/" + pb.bookingId() + "/cancel", tokenFor(c), "{}"))
                : statusOf(doPost("/api/admin/bookings/" + pb.bookingId() + "/complete", adminToken(), "{}")));

        assertThat(results).noneMatch(Outcome::failed);
        String status = jdbc.queryForObject("select status from bookings where id=?", String.class, pb.bookingId());
        long refunds = count("select count(*) from refunds where booking_id=?", pb.bookingId());
        if (status.equals("COMPLETED")) {
            assertThat(refunds).isZero();
        } else {
            assertThat(status).isEqualTo("CANCELLED");
            assertThat(refunds).isEqualTo(1);
            assertThat(booked(day, NINE)).isZero();
        }
    }
}
