package com.oorja.backend.dto.request;

import jakarta.validation.constraints.*;

/**
 * @param amountInr optional override — support can settle a different figure
 *                  than the policy computed (goodwill, partial settlement).
 *                  Still bounded server-side by the original payment.
 * @param note      why it was overridden or approved; stored on the audit trail
 */
public record ApproveRefundRequest(@Positive @Max(10000000) Double amountInr, @Size(max = 1000) String note) {

    public Long amountPaiseOrNull() {
        return amountInr == null ? null : Math.round(amountInr * 100);
    }
}
