package com.oorja.backend.dto.response;

import com.oorja.backend.entity.Invoice;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** An invoice as listed in the app. Amounts arrive in rupees, already computed. */
@Data
@Builder
public class InvoiceResponse {

    private Long id;
    private String invoiceNumber;
    private String type;
    private String status;
    private String statusLabel;

    private Long bookingId;
    private String bookingToken;
    private String description;

    private double subtotalInr;
    private double discountInr;
    private int taxRatePercent;
    private double taxInr;
    private double totalInr;
    private String currency;

    private String paymentMethod;
    private String paymentReference;
    private LocalDateTime paidAt;
    private LocalDateTime issuedAt;

    private String originalInvoiceNumber;
    private String fileName;

    public static InvoiceResponse from(Invoice i) {
        return InvoiceResponse.builder()
                .id(i.getId())
                .invoiceNumber(i.getInvoiceNumber())
                .type(i.getType().name())
                .status(i.getStatus().name())
                .statusLabel(i.getStatus().label())
                .bookingId(i.getBooking() != null ? i.getBooking().getId() : null)
                .bookingToken(i.getBooking() != null ? i.getBooking().getToken() : null)
                .description(i.getDescription())
                .subtotalInr(i.getSubtotalPaise() / 100.0)
                .discountInr(i.getDiscountPaise() / 100.0)
                .taxRatePercent(i.getTaxRateBps() / 100)
                .taxInr(i.getTaxPaise() / 100.0)
                .totalInr(i.getTotalPaise() / 100.0)
                .currency(i.getCurrency())
                .paymentMethod(i.getPaymentMethod())
                .paymentReference(i.getPaymentReference())
                .paidAt(i.getPaidAt())
                .issuedAt(i.getIssuedAt())
                .originalInvoiceNumber(i.getOriginalInvoice() != null ? i.getOriginalInvoice().getInvoiceNumber() : null)
                .fileName(i.fileName())
                .build();
    }
}
