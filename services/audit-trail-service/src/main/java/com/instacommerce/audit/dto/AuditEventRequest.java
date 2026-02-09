package com.instacommerce.audit.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AuditEventRequest(
    @NotBlank @Size(max = 100)
    String eventType,
    @NotBlank @Size(max = 50)
    String sourceService,
    UUID actorId,
    @Pattern(regexp = "USER|SYSTEM|ADMIN", message = "actorType must be USER, SYSTEM, or ADMIN")
    String actorType,
    @Size(max = 100)
    String resourceType,
    @Size(max = 255)
    String resourceId,
    @NotBlank @Size(max = 100)
    String action,
    Map<String, Object> details,
    @Size(max = 45)
    String ipAddress,
    @Size(max = 512)
    String userAgent,
    @Size(max = 64)
    String correlationId
) {
}
