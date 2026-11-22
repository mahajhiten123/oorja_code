package com.oorja.backend.dto.request;

import jakarta.validation.constraints.NotNull;

public record AssignTechnicianRequest(
        @NotNull Long technicianId
) {}
