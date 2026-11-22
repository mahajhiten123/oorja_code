package com.oorja.backend.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** Output of the ledger audit: the money position, plus anything that doesn't add up. */
@Data
@Builder
public class RefundReconciliationResponse {

    private LocalDateTime generatedAt;
    private int paymentsChecked;
    private double totalCapturedInr;
    private double totalRefundedInr;
    /** Captured minus refunded — what we should actually be holding. */
    private double netHeldInr;

    private long pendingApproval;
    private long inFlight;
    private long needingAttention;

    private int mismatchCount;
    private List<Mismatch> mismatches;

    @Data
    @Builder
    public static class Mismatch {
        private String paymentNumber;
        private String razorpayPaymentId;
        private double capturedInr;
        private double ledgerRefundedInr;
        private double refundRecordsInr;
        private String detail;
    }
}
