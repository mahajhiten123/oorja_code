package com.oorja.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param internal admin-only note; never rendered to the customer. Ignored
 *                 on the customer endpoint, which always sends false.
 */
public record TicketReplyRequest(
        @NotBlank @Size(max = 5000) String message,
        Boolean internal
) {}
