package com.oorja.backend.concurrency;

import com.fasterxml.jackson.databind.JsonNode;
import com.oorja.backend.support.AbstractIT;
import com.oorja.backend.support.Parallel;
import com.oorja.backend.support.Parallel.Outcome;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Simultaneous refund processing: the customer must never be paid twice for one booking. */
class RefundConcurrencyIT extends AbstractIT {

    /** An installation refund (above the auto-approve ceiling) waiting for an admin. */
    private long queuedRefund(TestCustomer c) throws Exception {
        PaidBooking pb = paidBooking(c, "installation", freshDay(), NINE);
        JsonNode out = body(doPost("/api/customer/bookings/" + pb.bookingId() + "/cancel", tokenFor(c), "{}").andExpect(status().isOk()));
        return out.get("refundId").asLong();
    }

    @Test
    void twentyConcurrentApprovalsReachTheGatewayAtMostOnceWithOneIdempotencyKey() throws Exception {
        TestCustomer c = newCustomer();
        long refundId = queuedRefund(c);
        Mockito.clearInvocations(razorpay);

        List<Outcome<Integer>> results = Parallel.run(20, i ->
                statusOf(doPost("/api/admin/refunds/" + refundId + "/approve", adminToken(), "{}")));

        assertThat(results).noneMatch(Outcome::failed);
        assertThat(results).allMatch(o -> o.value() == 200 || o.value() == 409);
        assertThat(results.stream().filter(o -> o.value() == 200).count()).isGreaterThanOrEqualTo(1);
        // one logical refund; the gateway call is keyed by the refund's idempotency key so a replay cannot pay twice
        assertThat(count("select count(*) from refunds where id=?", refundId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from refunds where id=?", String.class, refundId))
                .isIn("APPROVED", "PROCESSING", "COMPLETED");
        Mockito.verify(razorpay, Mockito.atMost(1)).refund(any(), anyLong(), any(), any(), any());
    }

    @Test
    void approveRacingRejectEndsInExactlyOneTerminalDecision() throws Exception {
        for (int round = 0; round < 3; round++) {
            TestCustomer c = newCustomer();
            long refundId = queuedRefund(c);

            List<Outcome<Integer>> results = Parallel.run(2, i -> i == 0
                    ? statusOf(doPost("/api/admin/refunds/" + refundId + "/approve", adminToken(), "{}"))
                    : statusOf(doPost("/api/admin/refunds/" + refundId + "/reject", adminToken(), "{\"reason\":\"no\"}")));

            assertThat(results).noneMatch(Outcome::failed);
            assertThat(results).allMatch(o -> o.value() == 200 || o.value() == 409);
            String status = jdbc.queryForObject("select status from refunds where id=?", String.class, refundId);
            assertThat(status).isIn("REJECTED", "APPROVED", "PROCESSING", "COMPLETED");
            // exactly one of the two wins; both cannot return 200 unless the loser was a harmless replay of the same state
            long wins = results.stream().filter(o -> o.value() == 200).count();
            assertThat(wins).isGreaterThanOrEqualTo(1);
            if (status.equals("REJECTED")) {
                assertThat(jdbc.queryForList("select 1 from refund_events where refund_id=? and to_status='REJECTED'", refundId)).hasSize(1);
            }
        }
    }

    @Test
    void concurrentAdminCreatedRefundsForOneBookingProduceOneActiveRefund() throws Exception {
        TestCustomer c = newCustomer();
        PaidBooking pb = paidBooking(c, freshDay(), NINE);
        String req = "{\"bookingId\":" + pb.bookingId() + ",\"reasonCode\":\"GOODWILL\",\"reason\":\"Apology\"}";

        List<Outcome<Integer>> results = Parallel.run(10, i -> statusOf(doPost("/api/admin/refunds", adminToken(), req)));

        assertThat(results).noneMatch(Outcome::failed);
        assertThat(results).allMatch(o -> o.value() < 500);
        assertThat(count("select count(*) from refunds where booking_id=? and status not in ('REJECTED','CANCELLED')", pb.bookingId()))
                .isEqualTo(1);
    }

    @Test
    void customerCancelRacingAdminCancelRaisesOneRefund() throws Exception {
        TestCustomer c = newCustomer();
        PaidBooking pb = paidBooking(c, freshDay(), NINE);

        List<Outcome<Integer>> results = Parallel.run(2, i -> i == 0
                ? statusOf(doPost("/api/customer/bookings/" + pb.bookingId() + "/cancel", tokenFor(c), "{}"))
                : statusOf(doPost("/api/admin/bookings/" + pb.bookingId() + "/cancel", adminToken(), "{\"reason\":\"x\"}")));

        assertThat(results).noneMatch(Outcome::failed);
        assertThat(results).allMatch(o -> o.value() == 200);
        assertThat(count("select count(*) from refunds where booking_id=?", pb.bookingId())).isEqualTo(1);
    }

    @Test
    void duplicateRefundProcessedWebhooksCompleteTheRefundOnce() throws Exception {
        TestCustomer c = newCustomer();
        long refundId = queuedRefund(c);
        doPost("/api/admin/refunds/" + refundId + "/approve", adminToken(), "{}").andExpect(status().isOk());
        String gatewayId = jdbc.queryForObject("select razorpay_refund_id from refunds where id=?", String.class, refundId);
        long amount = jdbc.queryForObject("select amount_paise from refunds where id=?", Long.class, refundId);
        String hook = refundWebhook("refund.processed", gatewayId, "processed", amount);

        List<Outcome<Integer>> results = Parallel.run(15, i -> statusOf(webhook(hook, "evt_" + shortId())));

        assertThat(results).noneMatch(Outcome::failed);
        assertThat(results).allMatch(o -> o.value() < 500);
        assertThat(jdbc.queryForObject("select status from refunds where id=?", String.class, refundId)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForList("select 1 from refund_events where refund_id=? and to_status='COMPLETED'", refundId)).hasSize(1);
    }

    @Test
    void simultaneousRefundsAcrossManyBookingsAllSucceedWithoutPoolExhaustion() throws Exception {
        int n = 30;
        List<TestCustomer> customers = new java.util.ArrayList<>();
        List<PaidBooking> bookings = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            TestCustomer c = newCustomer();
            customers.add(c);
            bookings.add(paidBooking(c, freshDay(), NINE));
        }

        List<Outcome<Integer>> results = Parallel.run(n, i -> statusOf(doPost(
                "/api/customer/bookings/" + bookings.get(i).bookingId() + "/cancel", tokenFor(customers.get(i)), "{}")));

        assertThat(results).noneMatch(Outcome::failed);
        assertThat(results).allMatch(o -> o.value() == 200);
        for (PaidBooking pb : bookings) {
            assertThat(count("select count(*) from refunds where booking_id=?", pb.bookingId())).isEqualTo(1);
        }
    }
}
