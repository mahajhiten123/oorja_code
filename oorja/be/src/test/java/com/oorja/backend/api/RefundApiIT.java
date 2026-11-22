package com.oorja.backend.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.oorja.backend.support.AbstractIT;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RefundApiIT extends AbstractIT {

    /**
     * An installation-sized payment exceeds the auto-approve ceiling, so its refund waits in the admin queue.
     * Returns the refund id.
     */
    private long queuedRefund(TestCustomer c, LocalDate day) throws Exception {
        PaidBooking pb = paidBooking(c, "installation", day, NINE);
        JsonNode out = body(doPost("/api/customer/bookings/" + pb.bookingId() + "/cancel", tokenFor(c), "{}").andExpect(status().isOk()));
        return out.get("refundId").asLong();
    }

    @Test
    void customerCancelReturnsRefundIdAndCustomerCanReadItsTimeline() throws Exception {
        TestCustomer c = newCustomer();
        PaidBooking pb = paidBooking(c, freshDay(), NINE);

        JsonNode out = body(doPost("/api/customer/bookings/" + pb.bookingId() + "/cancel", tokenFor(c), "{}").andExpect(status().isOk()));
        long refundId = out.get("refundId").asLong();

        JsonNode refund = body(doGet("/api/customer/refunds/" + refundId, tokenFor(c)).andExpect(status().isOk()));
        assertThat(refund.get("bookingId").asLong()).isEqualTo(pb.bookingId());
        assertThat(refund.get("status").asText()).isNotEqualTo("REJECTED");
        assertThat(body(doGet("/api/customer/refunds", tokenFor(c)).andExpect(status().isOk())).findValuesAsText("id"))
                .contains(String.valueOf(refundId));
    }

    @Test
    void adminApproveSendsToGatewayOnceAndRepeatedApproveDoesNotDoubleRefund() throws Exception {
        TestCustomer c = newCustomer();
        long refundId = queuedRefund(c, freshDay());

        doPost("/api/admin/refunds/" + refundId + "/approve", adminToken(), "{}").andExpect(status().isOk());
        int second = statusOf(doPost("/api/admin/refunds/" + refundId + "/approve", adminToken(), "{}"));

        assertThat(second).isIn(200, 409);
        org.mockito.Mockito.verify(razorpay, org.mockito.Mockito.atMost(1))
                .refund(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertThat(jdbc.queryForObject("select status from refunds where id=?", String.class, refundId))
                .isIn("APPROVED", "PROCESSING", "COMPLETED");
    }

    @Test
    void adminRejectIsTerminalAndCannotThenBeApproved() throws Exception {
        TestCustomer c = newCustomer();
        long refundId = queuedRefund(c, freshDay());

        doPost("/api/admin/refunds/" + refundId + "/reject", adminToken(), "{\"reason\":\"Service already delivered\"}")
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("select status from refunds where id=?", String.class, refundId)).isEqualTo("REJECTED");
        doPost("/api/admin/refunds/" + refundId + "/approve", adminToken(), "{}").andExpect(status().isConflict());
    }

    @Test
    void refundProcessedWebhookCompletesTheRefund() throws Exception {
        TestCustomer c = newCustomer();
        long refundId = queuedRefund(c, freshDay());
        doPost("/api/admin/refunds/" + refundId + "/approve", adminToken(), "{}").andExpect(status().isOk());
        String gatewayRefundId = jdbc.queryForObject("select razorpay_refund_id from refunds where id=?", String.class, refundId);
        long amount = jdbc.queryForObject("select amount_paise from refunds where id=?", Long.class, refundId);
        assertThat(gatewayRefundId).isNotBlank();

        webhook(refundWebhook("refund.processed", gatewayRefundId, "processed", amount), "evt_" + shortId()).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("select status from refunds where id=?", String.class, refundId)).isEqualTo("COMPLETED");
    }

    @Test
    void adminCanRaiseGoodwillRefundButNotTwiceForTheSameBooking() throws Exception {
        TestCustomer c = newCustomer();
        PaidBooking pb = paidBooking(c, freshDay(), NINE);
        String req = "{\"bookingId\":" + pb.bookingId() + ",\"reasonCode\":\"GOODWILL\",\"reason\":\"Apology\"}";

        doPost("/api/admin/refunds", adminToken(), req).andExpect(status().isOk());
        int second = statusOf(doPost("/api/admin/refunds", adminToken(), req));

        assertThat(second).isGreaterThanOrEqualTo(400);
        assertThat(count("select count(*) from refunds where booking_id=? and status not in ('REJECTED','CANCELLED')", pb.bookingId()))
                .isEqualTo(1);
    }

    @Test
    void refundQueueListsOnlyOpenItemsForAdmin() throws Exception {
        TestCustomer c = newCustomer();
        long refundId = queuedRefund(c, freshDay());

        JsonNode queue = body(doGet("/api/admin/refunds/queue", adminToken()).andExpect(status().isOk()));

        assertThat(queue.findValuesAsText("id")).contains(String.valueOf(refundId));
    }

    @Test
    void reconciliationReportIsAvailableToAdmin() throws Exception {
        doGet("/api/admin/refunds/reconciliation", adminToken()).andExpect(status().isOk());
    }
}
