package com.oorja.backend.dto.response;

import com.oorja.backend.entity.RefundEvent;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** One line of a refund's audit trail, safe to render straight into the customer app. */
@Data
@Builder
public class RefundEventResponse {
    private Long id;
    private String type;
    private String status;
    private String actorType;
    private String actorLabel;
    private String message;
    private Double amountInr;
    private LocalDateTime at;

    public static RefundEventResponse from(RefundEvent e) {
        return RefundEventResponse.builder()
                .id(e.getId())
                .type(e.getType().name())
                .status(e.getToStatus() != null ? e.getToStatus().name() : null)
                .actorType(e.getActorType())
                .actorLabel(e.getActorLabel())
                .message(e.getMessage())
                .amountInr(e.getAmountPaise() != null ? e.getAmountPaise() / 100.0 : null)
                .at(e.getCreatedAt())
                .build();
    }
}
