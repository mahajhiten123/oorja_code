package com.oorja.backend.dto.request;

import com.oorja.backend.entity.enums.SupportCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Category is required and closed-set: it decides routing, priority and the
 * response time we promise, so it can't be free text the customer invents.
 * The optional references let a ticket point at what it's actually about.
 */
public record CreateTicketRequest(
        @NotNull SupportCategory category,
        @NotBlank @Size(max = 200) String subject,
        @NotBlank @Size(max = 5000) String message,
        Long bookingId,
        Long invoiceId,
        Long refundId
) {}
