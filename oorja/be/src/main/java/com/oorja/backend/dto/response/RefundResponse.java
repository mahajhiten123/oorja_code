package com.oorja.backend.dto.response;

import com.oorja.backend.entity.Refund;
import com.oorja.backend.entity.RefundEvent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The refund as both the customer app and the admin console see it.
 *
 * Amounts are exposed in rupees for display, but every one of them is
 * derived from the stored paise value — the frontend never does refund
 * arithmetic of its own, so there's exactly one place a rounding bug could
 * live and it isn't in TypeScript.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefundResponse {

    private Long id;
    private String refundNumber;
    private Long bookingId;
    private String bookingToken;
    private String serviceLabel;
    private Long customerId;
    private String customerName;
    private String customerPhone;

    // --- The breakdown, itemised ---
    private double grossInr;
    private int refundPercentBps;
    private double cancellationFeeInr;
    private double gatewayFeeInr;
    private double deductionsInr;
    /** The amount actually being paid back. */
    private double amountInr;

    // --- Why ---
    private String reasonCode;
    private String reason;
    private String policyTier;
    private String eligibilityNote;
    private String requestedByType;

    // --- Where it is ---
    private String status;
    private String statusLabel;
    private boolean open;
    private boolean autoApproved;
    private String approvedBy;
    private String adminNote;
    private String lastError;
    private Integer attemptCount;

    // --- Gateway ---
    private String razorpayPaymentId;
    private String razorpayRefundId;
    private String gatewayStatus;

    // --- Timeline ---
    private LocalDateTime requestedAt;
    private LocalDateTime approvedAt;
    private LocalDateTime initiatedAt;
    private LocalDateTime completedAt;
    private LocalDateTime rejectedAt;
    private LocalDateTime expectedSettlementAt;
    private LocalDateTime reconciledAt;
    private Integer settlementDays;

    private List<RefundEventResponse> events;

    public static RefundResponse from(Refund r, List<RefundEvent> events, int settlementDays) {
        return RefundResponse.builder()
                .id(r.getId())
                .refundNumber(r.getRefundNumber())
                .bookingId(r.getBooking().getId())
                .bookingToken(r.getBooking().getToken())
                .serviceLabel(r.getBooking().getServiceLabel() != null
                        ? r.getBooking().getServiceLabel() : r.getBooking().getServiceType())
                .customerId(r.getCustomer().getId())
                .customerName(r.getCustomer().getName())
                .customerPhone(r.getCustomer().getPhone())
                .grossInr(r.getGrossAmountPaise() / 100.0)
                .refundPercentBps(r.getRefundPercentBps())
                .cancellationFeeInr(r.getCancellationFeePaise() / 100.0)
                .gatewayFeeInr(r.getGatewayFeePaise() / 100.0)
                .deductionsInr(r.getTotalDeductionsPaise() / 100.0)
                .amountInr(r.getAmountPaise() / 100.0)
                .reasonCode(r.getReasonCode().name())
                .reason(r.getReason())
                .policyTier(r.getPolicyTier())
                .eligibilityNote(r.getEligibilityNote())
                .requestedByType(r.getRequestedByType().name())
                .status(r.getStatus().name())
                .statusLabel(r.getStatus().label())
                .open(r.getStatus().isOpen())
                .autoApproved(Boolean.TRUE.equals(r.getAutoApproved()))
                .approvedBy(r.getApprovedBy())
                .adminNote(r.getAdminNote())
                .lastError(r.getLastError())
                .attemptCount(r.getAttemptCount())
                .razorpayPaymentId(r.getRazorpayPaymentId())
                .razorpayRefundId(r.getRazorpayRefundId())
                .gatewayStatus(r.getGatewayStatus())
                .requestedAt(r.getRequestedAt())
                .approvedAt(r.getApprovedAt())
                .initiatedAt(r.getInitiatedAt())
                .completedAt(r.getCompletedAt())
                .rejectedAt(r.getRejectedAt())
                .expectedSettlementAt(r.getExpectedSettlementAt())
                .reconciledAt(r.getReconciledAt())
                .settlementDays(settlementDays)
                .events(events == null ? List.of() : events.stream().map(RefundEventResponse::from).toList())
                .build();
    }
}
