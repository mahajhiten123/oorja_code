package com.oorja.backend.concurrency;

import com.oorja.backend.support.AbstractIT;
import com.oorja.backend.support.Parallel;
import com.oorja.backend.support.Parallel.Outcome;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** 50 customers, one slot with 10 seats, everybody pressing "book" at the same instant. */
class BookingConcurrencyIT extends AbstractIT {

    private static final int CUSTOMERS = 50;
    private static final int CAPACITY = 10;

    @Test
    void fiftyCustomersBookingOneSlotNeverOversell() throws Exception {
        LocalDate day = freshDay();
        List<TestCustomer> customers = new ArrayList<>();
        for (int i = 0; i < CUSTOMERS; i++) customers.add(newCustomer());

        List<Outcome<Integer>> results = Parallel.run(CUSTOMERS, i -> {
            TestCustomer c = customers.get(i);
            return statusOf(doPost("/api/customer/bookings", tokenFor(c), bookingRequest(c, null, day, NINE)));
        });

        assertThat(results).noneMatch(Outcome::failed);
        List<Integer> codes = results.stream().map(Outcome::value).toList();
        assertThat(codes.stream().filter(s -> s == 200).count()).isEqualTo(CAPACITY);
        assertThat(codes.stream().filter(s -> s == 409).count()).isEqualTo(CUSTOMERS - CAPACITY);
        assertThat(codes).allMatch(s -> s == 200 || s == 409);
        assertThat(booked(day, NINE)).isEqualTo(CAPACITY);
        assertThat(count("select count(*) from bookings where scheduled_date=? and scheduled_time=? and status='PENDING' and slot_reserved=true",
                java.sql.Date.valueOf(day), java.sql.Time.valueOf(NINE))).isEqualTo(CAPACITY);
    }

    @Test
    void fiftyCustomersPayingForOneSlotNeverOversell() throws Exception {
        LocalDate day = freshDay();
        List<TestCustomer> customers = new ArrayList<>();
        for (int i = 0; i < CUSTOMERS; i++) customers.add(newCustomer());

        // The seat is claimed at create-order, i.e. before any money moves.
        List<Outcome<Integer>> results = Parallel.run(CUSTOMERS, i -> {
            TestCustomer c = customers.get(i);
            return statusOf(doPost("/api/customer/payments/create-order", tokenFor(c), orderRequest(c, "cleaning", day, ELEVEN)));
        });

        assertThat(results).noneMatch(Outcome::failed);
        assertThat(results.stream().filter(o -> o.value() == 200).count()).isEqualTo(CAPACITY);
        assertThat(results.stream().filter(o -> o.value() == 409).count()).isEqualTo(CUSTOMERS - CAPACITY);
        assertThat(booked(day, ELEVEN)).isEqualTo(CAPACITY);
    }

    @Test
    void oneCustomerDoubleClickingBookWithSameIdempotencyKeyCreatesOneBooking() throws Exception {
        LocalDate day = freshDay();
        TestCustomer c = newCustomer();
        String key = "idem-" + shortId();

        List<Outcome<Long>> results = Parallel.run(8, i -> {
            var r = doPost("/api/customer/bookings", tokenFor(c), bookingRequest(c, null, day, NINE), "Idempotency-Key", key);
            return statusOf(r) == 200 ? body(r).get("id").asLong() : -1L;
        });

        assertThat(results).noneMatch(Outcome::failed);
        assertThat(results.stream().map(Outcome::value).collect(Collectors.toSet())).hasSize(1).doesNotContain(-1L);
        assertThat(count("select count(*) from bookings where customer_id=?", c.id())).isEqualTo(1);
        assertThat(booked(day, NINE)).isEqualTo(1);
    }
}
