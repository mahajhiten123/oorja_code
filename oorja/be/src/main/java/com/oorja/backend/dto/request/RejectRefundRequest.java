package com.oorja.backend.dto.request;

import jakarta.validation.constraints.*;

public record RejectRefundRequest(
        @Size(max = 1000) String reason
) {}
