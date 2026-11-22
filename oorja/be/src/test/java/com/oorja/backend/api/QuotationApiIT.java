package com.oorja.backend.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.oorja.backend.dto.request.CreateBookingRequest;
import com.oorja.backend.support.AbstractIT;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class QuotationApiIT extends AbstractIT {

    private long requestQuote(TestCustomer c) throws Exception {
        CreateBookingRequest req = new CreateBookingRequest(c.plantId(), "Installation", freshDay(), NINE, "Roof is flat", "Pune",
                null, "Installation", true);
        long bookingId = body(doPost("/api/customer/bookings", tokenFor(c), req).andExpect(status().isOk())).get("id").asLong();
        return jdbc.queryForObject("select id from quotations where booking_id=?", Long.class, bookingId);
    }

    private String send(long id, String amount) throws Exception {
        return body(doPost("/api/admin/quotations/" + id + "/send", adminToken(),
                "{\"amountInr\":" + amount + ",\"validUntil\":\"" + LocalDate.now().plusDays(14) + "\"}").andExpect(status().isOk()))
                .get("status").asText();
    }

    @Test
    void quoteRequestCreatesADraftTheCustomerCannotSeeYet() throws Exception {
        TestCustomer c = newCustomer();
        long q = requestQuote(c);

        assertThat(jdbc.queryForObject("select status from quotations where id=?", String.class, q)).isEqualTo("DRAFT");
        assertThat(count("select count(*) from payments where customer_id=?", c.id())).isZero();
    }

    @Test
    void adminSendsQuoteCustomerViewsAndAccepts() throws Exception {
        TestCustomer c = newCustomer();
        long q = requestQuote(c);

        assertThat(send(q, "55000.00")).isEqualTo("SENT");
        doGet("/api/customer/quotations/" + q, tokenFor(c)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select status from quotations where id=?", String.class, q)).isEqualTo("VIEWED");

        doPost("/api/customer/quotations/" + q + "/accept", tokenFor(c), null).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select status from quotations where id=?", String.class, q)).isEqualTo("ACCEPTED");
    }

    @Test
    void customerCanRejectASentQuote() throws Exception {
        TestCustomer c = newCustomer();
        long q = requestQuote(c);
        send(q, "40000.00");

        doPost("/api/customer/quotations/" + q + "/reject", tokenFor(c), null).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("select status from quotations where id=?", String.class, q)).isEqualTo("REJECTED");
    }

    @Test
    void draftQuoteCannotBeAccepted() throws Exception {
        TestCustomer c = newCustomer();
        long q = requestQuote(c);

        assertThat(statusOf(doPost("/api/customer/quotations/" + q + "/accept", tokenFor(c), null))).isGreaterThanOrEqualTo(400);
        assertThat(jdbc.queryForObject("select status from quotations where id=?", String.class, q)).isEqualTo("DRAFT");
    }

    @Test
    void acceptedQuoteCanBePaidThroughTheOrderFlowAndRecorded() throws Exception {
        TestCustomer c = newCustomer();
        long q = requestQuote(c);
        send(q, "55000.00");
        doGet("/api/customer/quotations/" + q, tokenFor(c)).andExpect(status().isOk());
        doPost("/api/customer/quotations/" + q + "/accept", tokenFor(c), null).andExpect(status().isOk());

        JsonNode order = body(doPost("/api/customer/payments/create-order-for-quotation", tokenFor(c),
                "{\"quotationId\":" + q + ",\"receipt\":\"rcpt_" + shortId() + "\"}").andExpect(status().isOk()));
        String orderId = order.get("orderId").asText();
        assertThat(order.get("amount").asLong()).isGreaterThanOrEqualTo(5_500_000L);

        String paymentId = "pay_" + shortId();
        doPost("/api/customer/payments/verify", tokenFor(c),
                "{\"razorpay_order_id\":\"" + orderId + "\",\"razorpay_payment_id\":\"" + paymentId + "\",\"razorpay_signature\":\"sig\"}")
                .andExpect(status().isOk());
        doPost("/api/customer/quotations/" + q + "/record-payment", tokenFor(c), "{\"razorpayOrderId\":\"" + orderId + "\"}")
                .andExpect(status().isOk());

        assertThat(count("select count(*) from payments where razorpay_order_id=? and status='CAPTURED' and quotation_id=?", orderId, q))
                .isEqualTo(1);
    }

    @Test
    void unacceptedQuoteCannotBePaid() throws Exception {
        TestCustomer c = newCustomer();
        long q = requestQuote(c);
        send(q, "55000.00");

        assertThat(statusOf(doPost("/api/customer/payments/create-order-for-quotation", tokenFor(c),
                "{\"quotationId\":" + q + ",\"receipt\":\"rcpt_" + shortId() + "\"}"))).isGreaterThanOrEqualTo(400);
        assertThat(count("select count(*) from payments where customer_id=?", c.id())).isZero();
    }

    @Test
    void anotherCustomerCannotSeeAcceptOrPayForSomeoneElsesQuote() throws Exception {
        TestCustomer owner = newCustomer();
        TestCustomer other = newCustomer();
        long q = requestQuote(owner);
        send(q, "55000.00");

        assertThat(statusOf(doGet("/api/customer/quotations/" + q, tokenFor(other)))).isIn(403, 404);
        assertThat(statusOf(doPost("/api/customer/quotations/" + q + "/accept", tokenFor(other), null))).isIn(403, 404);
        assertThat(statusOf(doPost("/api/customer/payments/create-order-for-quotation", tokenFor(other),
                "{\"quotationId\":" + q + ",\"receipt\":\"r" + shortId() + "\"}"))).isGreaterThanOrEqualTo(400);
        assertThat(jdbc.queryForObject("select status from quotations where id=?", String.class, q)).isEqualTo("SENT");
    }

    @Test
    void customersCannotUseAdminQuotationEndpoints() throws Exception {
        TestCustomer c = newCustomer();
        long q = requestQuote(c);
        doPost("/api/admin/quotations/" + q + "/send", tokenFor(c),
                "{\"amountInr\":1000,\"validUntil\":\"" + LocalDate.now().plusDays(3) + "\"}").andExpect(status().isForbidden());
    }

    @Test
    void repricingASentQuoteCreatesANewVersion() throws Exception {
        TestCustomer c = newCustomer();
        long q = requestQuote(c);
        send(q, "50000.00");
        send(q, "47000.00");

        assertThat(count("select count(*) from quotation_versions where quotation_id=?", q)).isGreaterThanOrEqualTo(2);
    }
}
