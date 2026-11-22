package com.oorja.backend.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.oorja.backend.support.AbstractIT;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Customer A must never be able to read or change anything that belongs to customer B. */
class CustomerIsolationApiIT extends AbstractIT {

    @Test
    void bookingListContainsOnlyTheCallersBookings() throws Exception {
        TestCustomer a = newCustomer();
        TestCustomer b = newCustomer();
        PaidBooking ba = paidBooking(a, freshDay(), NINE);
        PaidBooking bb = paidBooking(b, freshDay(), NINE);

        JsonNode listA = body(doGet("/api/customer/bookings", tokenFor(a)).andExpect(status().isOk()));

        assertThat(listA.findValuesAsText("id")).contains(String.valueOf(ba.bookingId())).doesNotContain(String.valueOf(bb.bookingId()));
    }

    @Test
    void cannotCancelAnotherCustomersBooking() throws Exception {
        TestCustomer a = newCustomer();
        TestCustomer b = newCustomer();
        LocalDate day = freshDay();
        PaidBooking victim = paidBooking(a, day, NINE);

        doPost("/api/customer/bookings/" + victim.bookingId() + "/cancel", tokenFor(b), "{}").andExpect(status().isForbidden());

        assertThat(count("select count(*) from bookings where id=? and status='PENDING'", victim.bookingId())).isEqualTo(1);
        assertThat(count("select count(*) from refunds where booking_id=?", victim.bookingId())).isZero();
        assertThat(booked(day, NINE)).isEqualTo(1);
    }

    @Test
    void cannotRescheduleAnotherCustomersBooking() throws Exception {
        TestCustomer a = newCustomer();
        TestCustomer b = newCustomer();
        LocalDate day = freshDay();
        PaidBooking victim = paidBooking(a, day, NINE);

        doPost("/api/customer/bookings/" + victim.bookingId() + "/reschedule", tokenFor(b),
                "{\"scheduledDate\":\"" + freshDay() + "\",\"scheduledTime\":\"11:00:00\"}").andExpect(status().isForbidden());

        assertThat(count("select count(*) from bookings where id=? and scheduled_date=?", victim.bookingId(), java.sql.Date.valueOf(day)))
                .isEqualTo(1);
    }

    @Test
    void cannotPreviewCancellationOfAnotherCustomersBooking() throws Exception {
        TestCustomer a = newCustomer();
        TestCustomer b = newCustomer();
        PaidBooking victim = paidBooking(a, freshDay(), NINE);

        assertThat(statusOf(doGet("/api/customer/bookings/" + victim.bookingId() + "/cancellation-preview", tokenFor(b))))
                .isIn(403, 404);
    }

    @Test
    void cannotReadOrRebookAnotherCustomersRefund() throws Exception {
        TestCustomer a = newCustomer();
        TestCustomer b = newCustomer();
        PaidBooking pb = paidBooking(a, freshDay(), NINE);
        long refundId = body(doPost("/api/customer/bookings/" + pb.bookingId() + "/cancel", tokenFor(a), "{}")
                .andExpect(status().isOk())).get("refundId").asLong();

        doGet("/api/customer/refunds/" + refundId, tokenFor(b)).andExpect(status().isForbidden());
        doPost("/api/customer/refunds/" + refundId + "/rebook", tokenFor(b),
                "{\"scheduledDate\":\"" + freshDay() + "\",\"scheduledTime\":\"13:00:00\"}").andExpect(status().isForbidden());
        assertThat(body(doGet("/api/customer/refunds", tokenFor(b)).andExpect(status().isOk())).findValuesAsText("id"))
                .doesNotContain(String.valueOf(refundId));
    }

    @Test
    void cannotBookAgainstAnotherCustomersPlant() throws Exception {
        TestCustomer a = newCustomer();
        TestCustomer b = newCustomer();

        doPost("/api/customer/bookings", tokenFor(b), bookingRequest(a, null, freshDay(), NINE)) // a's plantId, b's token
                .andExpect(status().isForbidden());
        assertThat(count("select count(*) from bookings where customer_id=?", b.id())).isZero();
    }

    @Test
    void cannotSpendAnotherCustomersPaidOrder() throws Exception {
        TestCustomer a = newCustomer();
        TestCustomer b = newCustomer();
        LocalDate day = freshDay();
        Paid paid = pay(a, "cleaning", day, NINE);

        int s = statusOf(doPost("/api/customer/bookings", tokenFor(b), bookingRequest(b, paid.orderId(), day, NINE)));

        assertThat(s).isGreaterThanOrEqualTo(400);
        assertThat(count("select count(*) from bookings where customer_id=?", b.id())).isZero();
        assertThat(count("select count(*) from payments where razorpay_order_id=? and booking_id is not null", paid.orderId())).isZero();
    }

    @Test
    void cannotDownloadAnotherCustomersAttachmentOrServiceReport() throws Exception {
        TestCustomer a = newCustomer();
        TestCustomer b = newCustomer();
        PaidBooking pb = paidBooking(a, freshDay(), NINE);

        assertThat(statusOf(doGet("/api/customer/bookings/attachments/999999999", tokenFor(b)))).isIn(403, 404);
        assertThat(statusOf(doGet("/api/customer/bookings/" + pb.bookingId() + "/service-report", tokenFor(b)))).isIn(403, 404);
    }
}
