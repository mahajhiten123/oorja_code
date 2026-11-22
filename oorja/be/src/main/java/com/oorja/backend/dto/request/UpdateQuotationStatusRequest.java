package com.oorja.backend.dto.request;

import com.oorja.backend.entity.enums.QuotationStatus;
import jakarta.validation.constraints.NotNull;

/** Used by the admin quick-actions ("Mark accepted" / "Mark rejected"). */
public record UpdateQuotationStatusRequest(@NotNull QuotationStatus status) {}
