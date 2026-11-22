package com.oorja.backend.dto.request;

import jakarta.validation.constraints.*;

import com.oorja.backend.entity.enums.PlantStatus;
import com.oorja.backend.entity.enums.PlantType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record PlantRequest(
        @NotBlank @Size(max = 150) String name,
        @NotNull PlantType type,
        @NotNull @Positive @Digits(integer = 8, fraction = 2) BigDecimal capacityKw,
        @NotBlank @Size(max = 255) String location,
        PlantStatus status,
        Long customerId
) {}
