package com.oorja.backend.concurrency;

import com.fasterxml.jackson.databind.JsonNode;
import com.oorja.backend.dto.request.VerifyPaymentRequest;
import com.oorja.backend.support.AbstractIT;
import com.oorja.backend.support.Parallel;
import com.oorja.backend.support.Parallel.Outcome;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentConcurrencyIT extends AbstractIT {

    private static final int N = 20;

    private String createOrder(TestCustomer c, LocalDate day) throws Exception {
        JsonNode order = body(doPost("/api/customer/payments/create-order", tokenFor(c),
                orderRequest(c, "cleaning", day, NINE)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()));
        return order.get("orderId").asText();
    }

    @Test
    void manySimultaneousVerifiesOfTheSamePaymentCaptureItExactlyOnce() throws Exception {
        TestCustomer c = newCustomer();
        LocalDate day = freshDay();
        String orderId = createOrder(c, day);
        String paymentId = "pay_" + shortId();

        List<Outcome<Integer>> results = Parallel.run(N, i -> statusOf(doPost("/api/customer/payments/verify", tokenFor(c),
                new VerifyPaymentRequest(orderId, paymentId, "sig"))));

        assertThat(results).noneMatch(Outcome::failed);
        assertThat(results).allMatch(o -> o.value() == 200);
        assertThat(count("select count(*) from payments where razorpay_order_id=? and status='CAPTURED' and razorpay_payment_id=?",
                orderId, paymentId)).isEqualTo(1);
        assertThat(booked(day, NINE)).isEqualTo(1);
    }

    @Test
    void verifiesWithDifferentPaymentIdsForOneOrderCaptureOnlyOne() throws Exception {
        TestCustomer c = newCustomer();
        String orderId = createOrder(c, freshDay());

        List<Outcome<Integer>> results = Parallel.run(N, i -> statusOf(doPost("/api/customer/payments/verify", tokenFor(c),
                new VerifyPaymentRequest(orderId, "pay_" + shortId(), "sig"))));

        assertThat(results).noneMatch(Outcome::failed);
        assertThat(results).allMatch(o -> o.value() < 500);
        assertThat(count("select count(*) from payments where razorpay_order_id=? and status='CAPTURED'", orderId)).isEqualTo(1);
        assertThat(count("select count(distinct razorpay_payment_id) from payments where razorpay_order_id=?", orderId)).isEqualTo(1);
    }

    @Test
    void verifyRacingTheWebhookForTheSamePaymentCapturesOnce() throws Exception {
        TestCustomer c = newCustomer();
        LocalDate day = freshDay();
        JsonNode order = body(doPost("/api/customer/payments/create-order", tokenFor(c),
                orderRequest(c, "cleaning", day, NINE)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()));
        String orderId = order.get("orderId").asText();
        long amount = order.get("amount").asLong();
        String paymentId = "pay_" + shortId();
        String hook = capturedWebhook(orderId, paymentId, amount);

        List<Outcome<Integer>> results = Parallel.run(N, i -> i % 2 == 0
                ? statusOf(doPost("/api/customer/payments/verify", tokenFor(c), new VerifyPaymentRequest(orderId, paymentId, "sig")))
                : statusOf(webhook(hook, "evt_" + shortId())));

        assertThat(results).noneMatch(Outcome::failed);
        assertThat(results).allMatch(o -> o.value() < 500);
        assertThat(count("select count(*) from payments where razorpay_order_id=? and status='CAPTURED'", orderId)).isEqualTo(1);
        assertThat(count("select count(*) from bookings where customer_id=?", c.id())).isLessThanOrEqualTo(1);
        assertThat(booked(day, NINE)).isEqualTo(1);
    }

    @Test
    void duplicateRazorpayWebhooksWithTheSameEventIdAreProcessedOnce() throws Exception {
        TestCustomer c = newCustomer();
        LocalDate day = freshDay();
        JsonNode order = body(doPost("/api/customer/payments/create-order", tokenFor(c),
                orderRequest(c, "cleaning", day, NINE)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()));
        String orderId = order.get("orderId").asText();
        String hook = capturedWebhook(orderId, "pay_" + shortId(), order.get("amount").asLong());
        String eventId = "evt_" + shortId();

        List<Outcome<Integer>> results = Parallel.run(N, i -> statusOf(webhook(hook, eventId)));

        assertThat(results).noneMatch(Outcome::failed);
        assertThat(results).allMatch(o -> o.value() < 500);
        assertThat(count("select count(*) from webhook_events where event_id=?", eventId)).isEqualTo(1);
        assertThat(count("select count(*) from payments where razorpay_order_id=? and status='CAPTURED'", orderId)).isEqualTo(1);
        assertThat(count("select count(*) from bookings where customer_id=?", c.id())).isLessThanOrEqualTo(1);
        assertThat(booked(day, NINE)).isEqualTo(1);
    }

    @Test
    void duplicateWebhooksWithDifferentEventIdsStillCreateOneBookingAndOneCapture() throws Exception {
        TestCustomer c = newCustomer();
        LocalDate day = freshDay();
        JsonNode order = body(doPost("/api/customer/payments/create-order", tokenFor(c),
                orderRequest(c, "cleaning", day, NINE)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()));
        String orderId = order.get("orderId").asText();
        String hook = capturedWebhook(orderId, "pay_" + shortId(), order.get("amount").asLong());

        List<Outcome<Integer>> results = Parallel.run(N, i -> statusOf(webhook(hook, "evt_" + shortId())));

        assertThat(results).noneMatch(Outcome::failed);
        assertThat(results).allMatch(o -> o.value() < 500);
        assertThat(count("select count(*) from payments where razorpay_order_id=? and status='CAPTURED'", orderId)).isEqualTo(1);
        assertThat(count("select count(*) from bookings where customer_id=?", c.id())).isLessThanOrEqualTo(1);
        assertThat(booked(day, NINE)).isEqualTo(1);
    }

    @Test
    void concurrentBookingCreationFromOnePaidOrderMakesOneBooking() throws Exception {
        TestCustomer c = newCustomer();
        LocalDate day = freshDay();
        Paid paid = pay(c, "cleaning", day, NINE);

        List<Outcome<Long>> results = Parallel.run(N, i -> {
            var r = doPost("/api/customer/bookings", tokenFor(c), bookingRequest(c, paid.orderId(), day, NINE));
            return statusOf(r) == 200 ? body(r).get("id").asLong() : -1L;
        });

        assertThat(results).noneMatch(Outcome::failed);
        long distinctBookings = results.stream().map(Outcome::value).filter(v -> v > 0).distinct().count();
        assertThat(distinctBookings).isEqualTo(1);
        assertThat(count("select count(*) from bookings where payment_ref=?", paid.paymentId())).isEqualTo(1);
        assertThat(booked(day, NINE)).isEqualTo(1);
    }
}
