package com.oorja.backend.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.oorja.backend.support.AbstractIT;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BookingLifecycleApiIT extends AbstractIT {

    @Test
    void paidBookingLifecyclePendingAssignedCompleted() throws Exception {
        TestCustomer c = newCustomer();
        PaidBooking pb = paidBooking(c, freshDay(), NINE);
        Long techId = jdbc.queryForObject(
                "insert into technicians(technician_code,name,phone,email,specialization,area) values (?,?,?,?,?,?) returning id",
                Long.class, "T" + shortId(), "Tech", "9000000000", shortId() + "@t.io", "Solar", "Pune");

        JsonNode assigned = body(doPost("/api/admin/bookings/" + pb.bookingId() + "/assign-technician", adminToken(),
                "{\"technicianId\":" + techId + "}").andExpect(status().isOk()));
        assertThat(assigned.get("status").asText()).isEqualTo("ASSIGNED");

        JsonNode done = body(doPost("/api/admin/bookings/" + pb.bookingId() + "/complete", adminToken(), "{}").andExpect(status().isOk()));
        assertThat(done.get("status").asText()).isEqualTo("COMPLETED");

        // completed bookings can be neither cancelled nor rescheduled
        doPost("/api/customer/bookings/" + pb.bookingId() + "/cancel", tokenFor(c), "{}").andExpect(status().isConflict());
        doPost("/api/customer/bookings/" + pb.bookingId() + "/reschedule", tokenFor(c),
                "{\"scheduledDate\":\"" + freshDay() + "\",\"scheduledTime\":\"11:00:00\"}").andExpect(status().isConflict());
        // completing again is idempotent
        doPost("/api/admin/bookings/" + pb.bookingId() + "/complete", adminToken(), "{}").andExpect(status().isOk());
    }

    @Test
    void customerSeesTheirBookingInTheList() throws Exception {
        TestCustomer c = newCustomer();
        PaidBooking pb = paidBooking(c, freshDay(), NINE);

        JsonNode list = body(doGet("/api/customer/bookings", tokenFor(c)).andExpect(status().isOk()));

        assertThat(list.isArray()).isTrue();
        assertThat(list.findValuesAsText("id")).contains(String.valueOf(pb.bookingId()));
    }

    @Test
    void unpaidBookingReservesSeatAndFullSlotIsRejectedWithSlotFull() throws Exception {
        LocalDate day = freshDay();
        for (int i = 0; i < 10; i++) {
            TestCustomer c = newCustomer();
            doPost("/api/customer/bookings", tokenFor(c), bookingRequest(c, null, day, NINE)).andExpect(status().isOk());
        }
        TestCustomer late = newCustomer();

        doPost("/api/customer/bookings", tokenFor(late), bookingRequest(late, null, day, NINE))
                .andExpect(status().isConflict())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("SLOT_FULL"));
        assertThat(booked(day, NINE)).isEqualTo(10);
    }

    @Test
    void offGridTimeIsRejected() throws Exception {
        TestCustomer c = newCustomer();
        doPost("/api/customer/bookings", tokenFor(c), bookingRequest(c, null, freshDay(), java.time.LocalTime.of(9, 17)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sameIdempotencyKeyReturnsTheSameBookingAndConsumesOneSeat() throws Exception {
        TestCustomer c = newCustomer();
        LocalDate day = freshDay();
        String key = "idem-" + shortId();

        long id1 = body(doPost("/api/customer/bookings", tokenFor(c), bookingRequest(c, null, day, NINE),
                "Idempotency-Key", key).andExpect(status().isOk())).get("id").asLong();
        long id2 = body(doPost("/api/customer/bookings", tokenFor(c), bookingRequest(c, null, day, NINE),
                "Idempotency-Key", key).andExpect(status().isOk())).get("id").asLong();

        assertThat(id2).isEqualTo(id1);
        assertThat(booked(day, NINE)).isEqualTo(1);
    }

    @Test
    void adminCancelRaisesAFullRefundAndReleasesTheSeat() throws Exception {
        TestCustomer c = newCustomer();
        LocalDate day = freshDay();
        PaidBooking pb = paidBooking(c, day, NINE);

        doPost("/api/admin/bookings/" + pb.bookingId() + "/cancel", adminToken(), "{\"reason\":\"Site unavailable\"}")
                .andExpect(status().isOk());

        assertThat(booked(day, NINE)).isZero();
        assertThat(jdbc.queryForObject("select reason_code from refunds where booking_id=?", String.class, pb.bookingId()))
                .isEqualTo("PROVIDER_CANCELLED");
        assertThat(jdbc.queryForObject("select amount_paise from refunds where booking_id=?", Long.class, pb.bookingId()))
                .isEqualTo(pb.paid().amountPaise());
    }

    @Test
    void rescheduleToFullSlotFailsAndKeepsTheOriginalSeat() throws Exception {
        LocalDate day = freshDay();
        LocalDate fullDay = freshDay();
        for (int i = 0; i < 10; i++) {
            TestCustomer filler = newCustomer();
            doPost("/api/customer/bookings", tokenFor(filler), bookingRequest(filler, null, fullDay, ELEVEN)).andExpect(status().isOk());
        }
        TestCustomer c = newCustomer();
        PaidBooking pb = paidBooking(c, day, NINE);

        doPost("/api/customer/bookings/" + pb.bookingId() + "/reschedule", tokenFor(c),
                "{\"scheduledDate\":\"" + fullDay + "\",\"scheduledTime\":\"11:00:00\"}").andExpect(status().isConflict());

        assertThat(booked(day, NINE)).isEqualTo(1);
        assertThat(booked(fullDay, ELEVEN)).isEqualTo(10);
    }

    @Test
    void cancellationPreviewChangesNothing() throws Exception {
        TestCustomer c = newCustomer();
        LocalDate day = freshDay();
        PaidBooking pb = paidBooking(c, day, NINE);

        doGet("/api/customer/bookings/" + pb.bookingId() + "/cancellation-preview", tokenFor(c)).andExpect(status().isOk());

        assertThat(booked(day, NINE)).isEqualTo(1);
        assertThat(count("select count(*) from refunds where booking_id=?", pb.bookingId())).isZero();
        assertThat(count("select count(*) from bookings where id=? and status='PENDING'", pb.bookingId())).isEqualTo(1);
    }

    @Test
    void paidBookingWithMoneyCannotBeDeletedByAdmin() throws Exception {
        TestCustomer c = newCustomer();
        PaidBooking pb = paidBooking(c, freshDay(), NINE);

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/admin/bookings/" + pb.bookingId())
                .header("Authorization", "Bearer " + adminToken())).andExpect(status().isConflict());
        assertThat(count("select count(*) from bookings where id=?", pb.bookingId())).isEqualTo(1);
    }

    @Test
    void validationErrorsUseTheStandardEnvelope() throws Exception {
        TestCustomer c = newCustomer();
        doPost("/api/customer/bookings", tokenFor(c), "{\"serviceType\":\"\"}")
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
