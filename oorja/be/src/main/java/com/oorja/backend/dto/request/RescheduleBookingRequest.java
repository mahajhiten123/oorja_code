package com.oorja.backend.dto.request;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.LocalTime;

public record RescheduleBookingRequest(
        @NotNull @FutureOrPresent LocalDate scheduledDate,
        @NotNull LocalTime scheduledTime
) {}
