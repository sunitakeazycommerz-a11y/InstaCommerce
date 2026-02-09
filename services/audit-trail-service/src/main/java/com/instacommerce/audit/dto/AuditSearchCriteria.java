package com.instacommerce.audit.dto;

import com.instacommerce.audit.exception.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;

public record AuditSearchCriteria(
    UUID actorId,
    String resourceType,
    String resourceId,
    String sourceService,
    String eventType,
    String correlationId,
    Instant fromDate,
    Instant toDate,
    int page,
    int size
) {
    public AuditSearchCriteria {
        if (page < 0) page = 0;
        if (size <= 0 || size > 100) size = 20;

        Instant resolvedToDate = toDate != null ? toDate : Instant.now();
        Instant resolvedFromDate = fromDate != null ? fromDate : resolvedToDate.minus(Duration.ofDays(30));

        if (resolvedFromDate.isAfter(resolvedToDate)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RANGE", "fromDate must be before toDate");
        }
        if (Duration.between(resolvedFromDate, resolvedToDate).toDays() > 366) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RANGE", "Query range cannot exceed 366 days");
        }

        fromDate = resolvedFromDate;
        toDate = resolvedToDate;
    }
}
