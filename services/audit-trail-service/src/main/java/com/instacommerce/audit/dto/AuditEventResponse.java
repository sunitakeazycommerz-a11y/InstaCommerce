package com.instacommerce.audit.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AuditEventResponse(
    UUID id,
    String eventType,
    String sourceService,
    UUID actorId,
    String actorType,
    String resourceType,
    String resourceId,
    String action,
    Map<String, Object> details,
    String ipAddress,
    String userAgent,
    String correlationId,
    Long sequenceNumber,
    String eventHash,
    String previousHash,
    Instant createdAt
) {
}
