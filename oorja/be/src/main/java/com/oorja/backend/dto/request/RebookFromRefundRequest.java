package com.oorja.backend.dto.request;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * "Actually, don't refund me — book me in for this slot instead."
 * Withdraws a pending refund and revives the cancelled booking at the new
 * time, keeping the original payment intact.
 */
public record RebookFromRefundRequest(
        @NotNull @FutureOrPresent LocalDate scheduledDate,
        @NotNull LocalTime scheduledTime
) {}
