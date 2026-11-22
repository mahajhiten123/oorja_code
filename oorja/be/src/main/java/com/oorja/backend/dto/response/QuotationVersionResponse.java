package com.oorja.backend.dto.response;

import com.oorja.backend.entity.QuotationVersion;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QuotationVersionResponse {
    private Long id;
    private Integer versionNumber;
    private BigDecimal subtotal;
    private BigDecimal discountAmount;
    private BigDecimal taxAmount;
    private BigDecimal amountInr;
    private String notes;
    private LocalDate validUntil;
    private String status;
    private boolean current;
    private boolean paid;
    private String createdByType;
    private LocalDateTime sentAt;
    private LocalDateTime viewedAt;
    private LocalDateTime respondedAt;
    private LocalDateTime createdAt;

    public static QuotationVersionResponse from(QuotationVersion v, boolean current) {
        return QuotationVersionResponse.builder()
                .id(v.getId())
                .versionNumber(v.getVersionNumber())
                .subtotal(v.getSubtotal())
                .discountAmount(v.getDiscountAmount())
                .taxAmount(v.getTaxAmount())
                .amountInr(v.getTotalAmount())
                .notes(v.getNotes())
                .validUntil(v.getValidUntil())
                .status(v.getStatus().name())
                .current(current)
                .paid(v.isPaid())
                .createdByType(v.getCreatedByType() != null ? v.getCreatedByType().name() : null)
                .sentAt(v.getSentAt())
                .viewedAt(v.getViewedAt())
                .respondedAt(v.getRespondedAt())
                .createdAt(v.getCreatedAt())
                .build();
    }
}
