package com.oorja.backend.dto.response;

import com.oorja.backend.entity.Payment;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Admin console -> Payments. One row of the payment ledger — a Razorpay
 * order from the moment it was created, whether or not it was ever paid.
 * {@code transactions} is left empty for the list view and populated only
 * for the single-payment detail view (see AdminPaymentController), since
 * fetching every payment's full audit trail just to render a table row
 * would be wasted work.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdminPaymentResponse {
    private Long id;
    private String paymentNumber;
    private Long customerId;
    private String customerName;
    private String customerPhone;
    private Long bookingId;
    private String bookingToken;
    private double amountInr;
    private String currency;
    private String status;
    private String serviceId;
    private String tierId;
    private Long quotationId;
    private String couponCode;
    private String razorpayOrderId;
    private String razorpayPaymentId;
    private LocalDateTime createdAt;
    private LocalDateTime capturedAt;
    private LocalDateTime failedAt;
    @Builder.Default
    private List<PaymentTransactionResponse> transactions = List.of();

    public static AdminPaymentResponse from(Payment p, List<PaymentTransactionResponse> transactions) {
        return AdminPaymentResponse.builder()
                .id(p.getId())
                .paymentNumber(p.getPaymentNumber())
                .customerId(p.getCustomer().getId())
                .customerName(p.getCustomer().getName())
                .customerPhone(p.getCustomer().getPhone())
                .bookingId(p.getBooking() != null ? p.getBooking().getId() : null)
                .bookingToken(p.getBooking() != null ? p.getBooking().getToken() : null)
                .amountInr(p.getAmountPaise() / 100.0)
                .currency(p.getCurrency())
                .status(p.getStatus().name())
                .serviceId(p.getServiceId())
                .tierId(p.getTierId())
                .quotationId(p.getQuotationId())
                .couponCode(p.getCouponCode())
                .razorpayOrderId(p.getRazorpayOrderId())
                .razorpayPaymentId(p.getRazorpayPaymentId())
                .createdAt(p.getCreatedAt())
                .capturedAt(p.getCapturedAt())
                .failedAt(p.getFailedAt())
                .transactions(transactions)
                .build();
    }
}
