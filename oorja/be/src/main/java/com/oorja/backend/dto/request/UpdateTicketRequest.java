package com.oorja.backend.dto.request;

import jakarta.validation.constraints.*;

import com.oorja.backend.entity.enums.SupportPriority;
import com.oorja.backend.entity.enums.SupportTicketStatus;

/** Triage from the admin console. Every field is optional — send only what changes. */
public record UpdateTicketRequest(
        SupportTicketStatus status,
        SupportPriority priority,
        @Size(max = 120) String assignedTo,
        @Size(max = 5000) String resolutionNote
) {}
