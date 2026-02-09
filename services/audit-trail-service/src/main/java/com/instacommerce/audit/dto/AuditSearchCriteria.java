package com.instacommerce.audit.dto;

import java.time.Instant;
import java.util.UUID;

public record AuditSearchCriteria(
    UUID actorId,
    String resourceType,
    String resourceId,
    String sourceService,
    String eventType,
    Instant fromDate,
    Instant toDate,
    int page,
    int size
) {
    public AuditSearchCriteria {
        if (page < 0) page = 0;
        if (size <= 0 || size > 100) size = 20;
    }
}
