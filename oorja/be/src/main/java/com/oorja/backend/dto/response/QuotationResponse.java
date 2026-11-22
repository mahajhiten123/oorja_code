package com.oorja.backend.dto.response;

import com.oorja.backend.entity.Quotation;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Shape is kept backward-compatible with the admin console's existing
 * Quotation type (customerName/plantType/capacityKw/siteAddress/amountInr/
 * status of PENDING|SENT|ACCEPTED|REJECTED) while adding the richer
 * production fields (quotationNumber, bookingId, line-item breakdown,
 * attachments, timestamps) additively. DRAFT/VIEWED/EXPIRED are reported
 * as-is - the admin console's TS types recognise all of them.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QuotationResponse {
    private Long id;
    private String quotationNumber;
    private Long bookingId;
    private String bookingToken;
    private Long customerId;
    private String customerName;
    private String customerPhone;
    private String customerEmail;
    private String plantType;
    private BigDecimal capacityKw;
    private String siteAddress;
    private String serviceType;
    private LocalDateTime requestedAt;
    private String status;
    private BigDecimal subtotal;
    private BigDecimal discountAmount;
    private BigDecimal taxAmount;
    /** Kept as "amountInr" (not totalAmount) to match the existing admin console contract. */
    private BigDecimal amountInr;
    private LocalDate validUntil;
    private String notes;
    private LocalDateTime sentAt;
    private LocalDateTime respondedAt;
    private boolean paid;
    /**
     * Which version these fields reflect (the "Revision N" shown to the
     * customer). See {@code GET .../versions} for the full history and
     * {@code GET .../revision-requests} for open change requests.
     */
    private Integer currentVersionNumber;
    private List<BookingAttachmentResponse> attachments;

    public static QuotationResponse from(Quotation q, List<BookingAttachmentResponse> attachments) {
        var booking = q.getBooking();
        var plant = booking.getPlant();
        var customer = q.getCustomer();
        return QuotationResponse.builder()
                .id(q.getId())
                .quotationNumber(q.getQuotationNumber())
                .bookingId(booking.getId())
                .bookingToken(booking.getToken())
                .customerId(customer.getId())
                .customerName(customer.getName())
                .customerPhone(customer.getPhone())
                .customerEmail(customer.getEmail())
                .plantType(plant.getType().name())
                .capacityKw(plant.getCapacityKw())
                .siteAddress(booking.getLocation() != null ? booking.getLocation() : plant.getLocation())
                .serviceType(booking.getServiceType())
                .requestedAt(booking.getCreatedAt())
                .status(q.getStatus().name())
                .subtotal(q.getSubtotal())
                .discountAmount(q.getDiscountAmount())
                .taxAmount(q.getTaxAmount())
                .amountInr(q.getTotalAmount())
                .validUntil(q.getValidUntil())
                .notes(q.getNotes())
                .sentAt(q.getSentAt())
                .respondedAt(q.getRespondedAt())
                .paid(q.getPaymentRef() != null)
                .currentVersionNumber(q.getCurrentVersionNumber())
                .attachments(attachments)
                .build();
    }
}
