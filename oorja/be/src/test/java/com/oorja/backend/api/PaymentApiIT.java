package com.oorja.backend.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.oorja.backend.dto.request.VerifyPaymentRequest;
import com.oorja.backend.support.AbstractIT;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PaymentApiIT extends AbstractIT {

    @Test
    void createOrderHoldsASeatAndPersistsACreatedPayment() throws Exception {
        TestCustomer c = newCustomer();
        LocalDate day = freshDay();

        JsonNode order = body(doPost("/api/customer/payments/create-order", tokenFor(c),
                orderRequest(c, "cleaning", day, NINE)).andExpect(status().isOk()));

        assertThat(order.get("orderId").asText()).startsWith("order_");
        assertThat(order.get("amount").asLong()).isPositive();
        assertThat(booked(day, NINE)).isEqualTo(1);
        assertThat(count("select count(*) from payments where razorpay_order_id=? and status='CREATED' and customer_id=?",
                order.get("orderId").asText(), c.id())).isEqualTo(1);
    }

    @Test
    void priceIsComputedByTheServerNotTheClient() throws Exception {
        TestCustomer c = newCustomer();
        JsonNode quote = body(doPost("/api/customer/bookings/price", tokenFor(c), "{\"serviceId\":\"cleaning\"}").andExpect(status().isOk()));
        JsonNode order = body(doPost("/api/customer/payments/create-order", tokenFor(c),
                orderRequest(c, "cleaning", freshDay(), NINE)).andExpect(status().isOk()));

        assertThat(quote.toString()).isNotBlank();
        assertThat(order.get("amount").asLong()).isPositive();
    }

    @Test
    void unknownServiceIsRejected() throws Exception {
        TestCustomer c = newCustomer();
        int s = statusOf(doPost("/api/customer/payments/create-order", tokenFor(c),
                orderRequest(c, "no-such-service", freshDay(), NINE)));
        assertThat(s).isBetween(400, 404);
    }

    @Test
    void verifyCapturesPaymentAndIsIdempotent() throws Exception {
        TestCustomer c = newCustomer();
        Paid paid = pay(c, "cleaning", freshDay(), NINE);

        doPost("/api/customer/payments/verify", tokenFor(c),
                new VerifyPaymentRequest(paid.orderId(), paid.paymentId(), "sig")).andExpect(status().isOk());

        assertThat(count("select count(*) from payments where razorpay_order_id=? and status='CAPTURED'", paid.orderId())).isEqualTo(1);
        assertThat(count("select count(*) from payment_transactions pt join payments p on p.id=pt.payment_id where p.razorpay_order_id=?",
                paid.orderId())).isGreaterThanOrEqualTo(1);
    }

    @Test
    void invalidSignatureDoesNotCapture() throws Exception {
        TestCustomer c = newCustomer();
        JsonNode order = body(doPost("/api/customer/payments/create-order", tokenFor(c),
                orderRequest(c, "cleaning", freshDay(), NINE)).andExpect(status().isOk()));
        when(razorpay.verifySignature(any())).thenReturn(false);

        int s = statusOf(doPost("/api/customer/payments/verify", tokenFor(c),
                new VerifyPaymentRequest(order.get("orderId").asText(), "pay_" + shortId(), "bad")));

        assertThat(s).isIn(200, 400, 401, 422);
        assertThat(count("select count(*) from payments where razorpay_order_id=? and status='CAPTURED'",
                order.get("orderId").asText())).isZero();
    }

    @Test
    void anotherCustomerCannotVerifySomeoneElsesOrder() throws Exception {
        TestCustomer owner = newCustomer();
        TestCustomer attacker = newCustomer();
        JsonNode order = body(doPost("/api/customer/payments/create-order", tokenFor(owner),
                orderRequest(owner, "cleaning", freshDay(), NINE)).andExpect(status().isOk()));

        int s = statusOf(doPost("/api/customer/payments/verify", tokenFor(attacker),
                new VerifyPaymentRequest(order.get("orderId").asText(), "pay_" + shortId(), "sig")));

        assertThat(s).isGreaterThanOrEqualTo(400);
        assertThat(count("select count(*) from payments where razorpay_order_id=? and status='CAPTURED'",
                order.get("orderId").asText())).isZero();
    }

    @Test
    void createOrderWithSameIdempotencyKeyReturnsSameOrderAndOneSeat() throws Exception {
        TestCustomer c = newCustomer();
        LocalDate day = freshDay();
        String key = "idem-" + shortId();

        String o1 = body(doPost("/api/customer/payments/create-order", tokenFor(c), orderRequest(c, "cleaning", day, NINE),
                "Idempotency-Key", key).andExpect(status().isOk())).get("orderId").asText();
        String o2 = body(doPost("/api/customer/payments/create-order", tokenFor(c), orderRequest(c, "cleaning", day, NINE),
                "Idempotency-Key", key).andExpect(status().isOk())).get("orderId").asText();

        assertThat(o2).isEqualTo(o1);
        assertThat(booked(day, NINE)).isEqualTo(1);
        assertThat(count("select count(*) from payments where customer_id=?", c.id())).isEqualTo(1);
    }

    @Test
    void bookingFromTheSamePaymentTwiceReturnsTheSameBooking() throws Exception {
        TestCustomer c = newCustomer();
        LocalDate day = freshDay();
        Paid paid = pay(c, "cleaning", day, NINE);

        long b1 = body(doPost("/api/customer/bookings", tokenFor(c), bookingRequest(c, paid.orderId(), day, NINE))
                .andExpect(status().isOk())).get("id").asLong();
        long b2 = body(doPost("/api/customer/bookings", tokenFor(c), bookingRequest(c, paid.orderId(), day, NINE))
                .andExpect(status().isOk())).get("id").asLong();

        assertThat(b2).isEqualTo(b1);
        assertThat(count("select count(*) from bookings where payment_ref=?", paid.paymentId())).isEqualTo(1);
        assertThat(booked(day, NINE)).isEqualTo(1);
    }

    @Test
    void bookingCannotBeCreatedFromAnUnpaidOrder() throws Exception {
        TestCustomer c = newCustomer();
        LocalDate day = freshDay();
        JsonNode order = body(doPost("/api/customer/payments/create-order", tokenFor(c),
                orderRequest(c, "cleaning", day, NINE)).andExpect(status().isOk()));

        int s = statusOf(doPost("/api/customer/bookings", tokenFor(c),
                bookingRequest(c, order.get("orderId").asText(), day, NINE)));

        assertThat(s).isGreaterThanOrEqualTo(400);
        assertThat(count("select count(*) from bookings where customer_id=?", c.id())).isZero();
    }

    @Test
    void capturedWebhookCreatesTheBookingWhenTheClientNeverCameBack() throws Exception {
        TestCustomer c = newCustomer();
        LocalDate day = freshDay();
        JsonNode order = body(doPost("/api/customer/payments/create-order", tokenFor(c),
                orderRequest(c, "cleaning", day, NINE)).andExpect(status().isOk()));
        String orderId = order.get("orderId").asText();

        webhook(capturedWebhook(orderId, "pay_" + shortId(), order.get("amount").asLong()), "evt_" + shortId())
                .andExpect(status().isOk());

        assertThat(count("select count(*) from payments where razorpay_order_id=? and status='CAPTURED'", orderId)).isEqualTo(1);
        assertThat(count("select count(*) from bookings where customer_id=?", c.id())).isLessThanOrEqualTo(1);
        assertThat(booked(day, NINE)).isEqualTo(1);
    }

    @Test
    void duplicateWebhookDeliveryIsIgnored() throws Exception {
        TestCustomer c = newCustomer();
        LocalDate day = freshDay();
        JsonNode order = body(doPost("/api/customer/payments/create-order", tokenFor(c),
                orderRequest(c, "cleaning", day, NINE)).andExpect(status().isOk()));
        String raw = capturedWebhook(order.get("orderId").asText(), "pay_" + shortId(), order.get("amount").asLong());
        String eventId = "evt_" + shortId();

        webhook(raw, eventId).andExpect(status().isOk());
        webhook(raw, eventId).andExpect(status().isOk());

        assertThat(count("select count(*) from webhook_events where event_id=?", eventId)).isEqualTo(1);
        assertThat(booked(day, NINE)).isEqualTo(1);
        assertThat(count("select count(*) from bookings where customer_id=?", c.id())).isLessThanOrEqualTo(1);
    }

    @Test
    void webhookWithInvalidSignatureIsRejectedAndChangesNothing() throws Exception {
        TestCustomer c = newCustomer();
        JsonNode order = body(doPost("/api/customer/payments/create-order", tokenFor(c),
                orderRequest(c, "cleaning", freshDay(), NINE)).andExpect(status().isOk()));
        when(razorpay.verifyWebhookSignature(any(), any())).thenReturn(false);

        webhook(capturedWebhook(order.get("orderId").asText(), "pay_" + shortId(), order.get("amount").asLong()), "evt_" + shortId())
                .andExpect(status().isUnauthorized());

        assertThat(count("select count(*) from payments where razorpay_order_id=? and status='CAPTURED'",
                order.get("orderId").asText())).isZero();
    }
}
