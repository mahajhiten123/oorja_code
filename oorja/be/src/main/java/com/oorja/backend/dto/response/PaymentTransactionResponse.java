package com.oorja.backend.dto.response;

import com.oorja.backend.entity.PaymentTransaction;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * One row of a Payment's append-only audit trail — shown to the admin as a
 * timeline under Admin -> Payments -> (payment detail).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentTransactionResponse {
    private Long id;
    private String type;
    private double amountInr;
    private String status;
    private String provider;
    private String providerTransactionId;
    private String metadata;
    private LocalDateTime createdAt;

    public static PaymentTransactionResponse from(PaymentTransaction t) {
        return PaymentTransactionResponse.builder()
                .id(t.getId())
                .type(t.getType().name())
                .amountInr(t.getAmountPaise() / 100.0)
                .status(t.getStatus())
                .provider(t.getProvider())
                .providerTransactionId(t.getProviderTransactionId())
                .metadata(t.getMetadata())
                .createdAt(t.getCreatedAt())
                .build();
    }
}
