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
    public static final int MAX_PAGE_SIZE = 100;
    public static final Duration MAX_QUERY_RANGE = Duration.ofDays(366);
    public static final Duration MAX_EXPORT_RANGE = Duration.ofDays(90);

    public AuditSearchCriteria {
        if (page < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGINATION", "page must not be negative");
        }
        if (size <= 0 || size > MAX_PAGE_SIZE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGINATION",
                "size must be between 1 and " + MAX_PAGE_SIZE);
        }

        resourceType = normalizeFilter("resourceType", resourceType, 100);
        resourceId = normalizeFilter("resourceId", resourceId, 255);
        sourceService = normalizeFilter("sourceService", sourceService, 100);
        eventType = normalizeFilter("eventType", eventType, 100);
        correlationId = normalizeFilter("correlationId", correlationId, 128);

        Instant resolvedToDate = toDate != null ? toDate : Instant.now();
        Instant resolvedFromDate = fromDate != null ? fromDate : resolvedToDate.minus(Duration.ofDays(30));

        if (!resolvedFromDate.isBefore(resolvedToDate)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RANGE", "fromDate must be before toDate");
        }
        if (Duration.between(resolvedFromDate, resolvedToDate).compareTo(MAX_QUERY_RANGE) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RANGE", "Query range cannot exceed 366 days");
        }

        fromDate = resolvedFromDate;
        toDate = resolvedToDate;
    }

    public static AuditSearchCriteria forExport(
        UUID actorId,
        String resourceType,
        String resourceId,
        String sourceService,
        String eventType,
        String correlationId,
        Instant fromDate,
        Instant toDate
    ) {
        AuditSearchCriteria criteria = new AuditSearchCriteria(
            actorId,
            resourceType,
            resourceId,
            sourceService,
            eventType,
            correlationId,
            fromDate,
            toDate,
            0,
            MAX_PAGE_SIZE
        );
        if (Duration.between(criteria.fromDate(), criteria.toDate()).compareTo(MAX_EXPORT_RANGE) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_EXPORT_RANGE",
                "Export date range cannot exceed 90 days");
        }
        return criteria;
    }

    private static String normalizeFilter(String fieldName, String raw, int maxLength) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > maxLength) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_FILTER",
                fieldName + " exceeds maximum length of " + maxLength);
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (Character.isISOControl(ch)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_FILTER",
                    fieldName + " must not contain control characters");
            }
        }
        return trimmed;
    }
}
