package com.oorja.backend.dto.request;

import jakarta.validation.constraints.*;

import com.oorja.backend.entity.enums.RevisionReason;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Customer's "Request Revision" form on a sent quotation. */
public record CreateRevisionRequestRequest(
        @NotNull RevisionReason reason,
        @NotBlank @Size(max = 5000) String message
) {}
