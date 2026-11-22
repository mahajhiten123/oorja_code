package com.oorja.backend.dto.request;

import jakarta.validation.constraints.*;

import jakarta.validation.constraints.NotNull;

public record SetTechnicianStatusRequest(
        @NotNull @Pattern(regexp = "AVAILABLE|OFF_DUTY", message = "must be AVAILABLE or OFF_DUTY") String status
) {}
