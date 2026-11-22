package com.oorja.backend.dto.request;

import jakarta.validation.constraints.*;

/** Optional note recorded when an admin rejects (declines to action) a revision request. */
public record ResolveRevisionRequestRequest(
        @Size(max = 2000) String note
) {}
