package com.oorja.backend.dto.request;

import jakarta.validation.constraints.*;

import com.oorja.backend.entity.enums.BookingStatus;

import java.time.LocalDate;
import java.time.LocalTime;

public record UpdateBookingRequest(
        BookingStatus status,
        Long technicianId,
        LocalDate scheduledDate,
        LocalTime scheduledTime,
        @Size(max = 2000) String notes
) {}
