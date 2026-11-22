package com.oorja.backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** "v2 → v3": the fields that actually changed between two versions, each as a from/to pair. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QuotationVersionDiffResponse {
    private Integer fromVersion;
    private Integer toVersion;
    private List<FieldChange> changes;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class FieldChange {
        /** e.g. "subtotal", "discountAmount", "taxAmount", "amountInr", "validUntil", "notes" */
        private String field;
        /** Human label for display, e.g. "Total" */
        private String label;
        private String from;
        private String to;
    }
}
