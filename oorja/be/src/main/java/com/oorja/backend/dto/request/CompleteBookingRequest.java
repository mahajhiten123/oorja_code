package com.oorja.backend.dto.request;

import jakarta.validation.constraints.*;

public record CompleteBookingRequest(
        @Size(max = 2000) String notes
) {}
