package com.oorja.backend.dto.request;

import jakarta.validation.constraints.*;

/** Optional reason recorded on the audit trail when an admin deletes a file. */
public record DeleteAttachmentRequest(
        @Size(max = 1000) String reason
) {}
