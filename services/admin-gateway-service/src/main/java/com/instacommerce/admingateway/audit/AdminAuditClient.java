package com.instacommerce.admingateway.audit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class AdminAuditClient {

    private static final Logger log = LoggerFactory.getLogger(AdminAuditClient.class);
    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final boolean enabled;
    private final String internalServiceName;
    private final String internalServiceToken;
    private final Counter auditFailuresCounter;

    public AdminAuditClient(
            RestTemplate restTemplate,
            @Value("${audit-trail.base-url:http://localhost:8096}") String auditTrailBaseUrl,
            @Value("${audit-trail.enabled:true}") boolean enabled,
            @Value("${internal.service.name:${spring.application.name:admin-gateway-service}}") String internalServiceName,
            @Value("${internal.service.token:}") String internalServiceToken,
            MeterRegistry meterRegistry) {
        this.restTemplate = restTemplate;
        this.baseUrl = auditTrailBaseUrl;
        this.enabled = enabled;
        this.internalServiceName = internalServiceName;
        this.internalServiceToken = internalServiceToken;
        this.auditFailuresCounter = Counter.builder("admin.audit.failures")
            .description("Total failures publishing admin audit events to audit-trail-service")
            .register(meterRegistry);
    }

    public void publishAuditEvent(
            String eventType,
            UUID actorId,
            String actorType,
            String resourceType,
            String resourceId,
            String action,
            Map<String, Object> details,
            String ipAddress,
            String userAgent,
            String correlationId) {
        if (!enabled) {
            return;
        }

        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("eventType", eventType);
            payload.put("sourceService", "admin-gateway-service");
            if (actorId != null) {
                payload.put("actorId", actorId.toString());
            }
            payload.put("actorType", actorType != null ? actorType : "ADMIN");
            payload.put("resourceType", resourceType != null ? resourceType : "UNKNOWN");
            payload.put("resourceId", resourceId != null ? resourceId : "N/A");
            payload.put("action", action);
            payload.put("details", details != null ? details : Map.of());
            payload.put("ipAddress", ipAddress);
            payload.put("userAgent", userAgent);
            payload.put("correlationId", correlationId);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (internalServiceName != null && !internalServiceName.isBlank()) {
                headers.set("X-Internal-Service", internalServiceName);
            }
            if (internalServiceToken != null && !internalServiceToken.isBlank()) {
                headers.set("X-Internal-Token", internalServiceToken);
            }
            if (correlationId != null && !correlationId.isBlank()) {
                headers.set("X-Correlation-ID", correlationId);
            }

            HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(payload, headers);
            restTemplate.postForLocation(baseUrl + "/audit/events", requestEntity);
            log.debug("Successfully published audit event to audit-trail-service: action={} resourceType={}",
                    action, resourceType);
        } catch (Exception ex) {
            auditFailuresCounter.increment();
            log.warn("Failed to publish audit event to audit-trail-service: {}", ex.getMessage());
        }
    }
}
