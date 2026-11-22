package com.oorja.backend.dto.request;

import jakarta.validation.constraints.*;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * What the technician or admin fills in after a visit.
 *
 * @param checklistJson JSON array of {"label","ok","note"} — free-form so
 *                      ops can change the checklist per service type without
 *                      a migration. Validated at render time, not here.
 */
public record ServiceReportRequest(
        LocalDate serviceDate,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        @NotBlank @Size(max = 5000) String workPerformed,
        @Size(max = 5000) String observations,
        @Size(max = 5000) String partsReplaced,
        @Size(max = 5000) String recommendations,
        @Size(max = 20000) String checklistJson,
        @PositiveOrZero @Max(100000) Integer panelsCleaned,
        @PositiveOrZero @Digits(integer = 8, fraction = 2) BigDecimal outputBeforeKw,
        @PositiveOrZero @Digits(integer = 8, fraction = 2) BigDecimal outputAfterKw,
        LocalDate nextServiceDue,
        @Size(max = 160) String customerSignatureName,
        @Size(max = 5000) String customerRemarks
) {
    /** Cross-field rule: a visit can't finish before it started. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @AssertTrue(message = "finishedAt must not be before startedAt")
    public boolean isTimeRangeValid() {
        return startedAt == null || finishedAt == null || !finishedAt.isBefore(startedAt);
    }
}
