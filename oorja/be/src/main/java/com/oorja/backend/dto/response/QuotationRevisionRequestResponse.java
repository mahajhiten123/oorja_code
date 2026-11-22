package com.oorja.backend.dto.response;

import com.oorja.backend.entity.QuotationRevisionRequest;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QuotationRevisionRequestResponse {
    private Long id;
    private Long quotationId;
    private Integer quotationVersionNumber;
    private String reason;
    private String message;
    private String status;
    private String resolutionNote;
    private LocalDateTime createdAt;
    private LocalDateTime resolvedAt;

    public static QuotationRevisionRequestResponse from(QuotationRevisionRequest r) {
        return QuotationRevisionRequestResponse.builder()
                .id(r.getId())
                .quotationId(r.getQuotation().getId())
                .quotationVersionNumber(r.getQuotationVersion().getVersionNumber())
                .reason(r.getReason().name())
                .message(r.getMessage())
                .status(r.getStatus().name())
                .resolutionNote(r.getResolutionNote())
                .createdAt(r.getCreatedAt())
                .resolvedAt(r.getResolvedAt())
                .build();
    }
}
