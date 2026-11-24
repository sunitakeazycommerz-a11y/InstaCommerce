package com.instacommerce.admingateway.controller;

import com.instacommerce.admingateway.service.DashboardAggregationService;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequestMapping("/admin/v1")
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
public class AdminDashboardController {
    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
        new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<Object> OBJECT_TYPE =
        new ParameterizedTypeReference<>() {};
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[a-zA-Z0-9._:@-]{1,128}$");
    private static final long MAX_OVERRIDE_TTL_SECONDS = 31_536_000L;

    private final RestTemplate restTemplate;
    private final DashboardAggregationService dashboardAggregationService;
    private final String featureFlagUrl;
    private final String reconciliationUrl;
    private final String fraudUrl;
    private final String auditUrl;

    public AdminDashboardController(
            RestTemplate restTemplate,
            DashboardAggregationService dashboardAggregationService,
            String featureFlagUrl,
            String reconciliationUrl) {
        this(
            restTemplate,
            dashboardAggregationService,
            featureFlagUrl,
            reconciliationUrl,
            "http://fraud-detection-service:8097",
            "http://audit-trail-service:8096");
    }

    @Autowired
    public AdminDashboardController(
            RestTemplate restTemplate,
            DashboardAggregationService dashboardAggregationService,
            @Value("${admin-gateway.upstream.feature-flag-url:http://config-feature-flag-service:8098}") String featureFlagUrl,
            @Value("${admin-gateway.upstream.reconciliation-url:http://reconciliation-engine:8107}") String reconciliationUrl,
            @Value("${admin-gateway.upstream.fraud-url:http://fraud-detection-service:8097}") String fraudUrl,
            @Value("${admin-gateway.upstream.audit-url:${audit-trail.base-url:http://audit-trail-service:8096}}") String auditUrl) {
        this.restTemplate = restTemplate;
        this.dashboardAggregationService = dashboardAggregationService;
        this.featureFlagUrl = normalizeBaseUrl(featureFlagUrl);
        this.reconciliationUrl = normalizeBaseUrl(reconciliationUrl);
        this.fraudUrl = normalizeBaseUrl(fraudUrl);
        this.auditUrl = normalizeBaseUrl(auditUrl);
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        return dashboardAggregationService.aggregate();
    }

    @GetMapping("/flags")
    public Map<String, Object> featureFlags() {
        var response = restTemplate.exchange(
            featureFlagUrl + "/admin/flags",
            HttpMethod.GET,
            null,
            OBJECT_TYPE
        );
        return normalizeObjectResponse("flags", response.getBody());
    }

    @PostMapping("/flags/{key}/enable")
    public Map<String, Object> enableFlag(@PathVariable String key) {
        String sanitizedKey = requireSafeIdentifier(key, "flag key");
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
            featureFlagUrl + "/admin/flags/{key}/enable",
            HttpMethod.POST,
            HttpEntity.EMPTY,
            MAP_TYPE,
            sanitizedKey
        );
        Map<String, Object> body = response.getBody();
        if (body != null && !body.isEmpty()) {
            return body;
        }
        return Map.of("flagKey", sanitizedKey, "enabled", true, "httpStatus", response.getStatusCode().value());
    }

    @PostMapping("/flags/{key}/disable")
    public Map<String, Object> disableFlag(@PathVariable String key) {
        String sanitizedKey = requireSafeIdentifier(key, "flag key");
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
            featureFlagUrl + "/admin/flags/{key}/disable",
            HttpMethod.POST,
            HttpEntity.EMPTY,
            MAP_TYPE,
            sanitizedKey
        );
        Map<String, Object> body = response.getBody();
        if (body != null && !body.isEmpty()) {
            return body;
        }
        return Map.of("flagKey", sanitizedKey, "enabled", false, "httpStatus", response.getStatusCode().value());
    }

    @PostMapping("/flags/{key}/emergency-stop")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Map<String, Object> emergencyStopFlag(@PathVariable String key) {
        String sanitizedKey = requireSafeIdentifier(key, "flag key");
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
            featureFlagUrl + "/admin/flags/emergency-stop/{key}",
            HttpMethod.POST,
            HttpEntity.EMPTY,
            MAP_TYPE,
            sanitizedKey
        );
        Map<String, Object> body = response.getBody();
        if (body != null && !body.isEmpty()) {
            return body;
        }
        return Map.of("flagKey", sanitizedKey, "status", "emergency_stopped", "httpStatus", response.getStatusCode().value());
    }

    @PostMapping("/flags/{id}/override")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Map<String, Object> overrideFlag(@PathVariable String id, @RequestBody Map<String, Object> request) {
        String sanitizedId = requireSafeIdentifier(id, "flag id");

        Map<String, Object> downstreamPayload = new HashMap<>(request != null ? request : Map.of());
        if (!downstreamPayload.containsKey("expiresAt")
                && downstreamPayload.get("ttlSeconds") instanceof Number ttlSeconds) {
            long ttl = ttlSeconds.longValue();
            if (ttl <= 0 || ttl > MAX_OVERRIDE_TTL_SECONDS) {
                throw new IllegalArgumentException("ttlSeconds must be between 1 and " + MAX_OVERRIDE_TTL_SECONDS);
            }
            downstreamPayload.put("expiresAt", Instant.now().plusSeconds(ttl).toString());
        }

        HttpEntity<Map<String, Object>> entity = jsonEntity(downstreamPayload, null);
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
            featureFlagUrl + "/admin/flags/{key}/overrides",
            HttpMethod.POST,
            entity,
            MAP_TYPE,
            sanitizedId
        );

        Map<String, Object> body = response.getBody();
        if (body != null && !body.isEmpty()) {
            return body;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("flagId", sanitizedId);
        result.put("status", "applied");
        result.put("httpStatus", response.getStatusCode().value());
        return result;
    }

    @GetMapping("/fraud/rules")
    public Map<String, Object> listFraudRules(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        URI uri = UriComponentsBuilder.fromUriString(fraudUrl + "/admin/fraud/rules")
            .queryParam("page", Math.max(0, page))
            .queryParam("size", Math.min(Math.max(1, size), 100))
            .build()
            .toUri();
        var response = restTemplate.exchange(uri, HttpMethod.GET, null, OBJECT_TYPE);
        return normalizeObjectResponse("rules", response.getBody());
    }

    @PostMapping("/fraud/rules")
    public Map<String, Object> createFraudRule(@RequestBody Map<String, Object> request) {
        var response = restTemplate.exchange(
            fraudUrl + "/admin/fraud/rules",
            HttpMethod.POST,
            jsonEntity(request != null ? request : Map.of(), null),
            MAP_TYPE
        );
        return response.getBody() != null ? response.getBody() : Map.of();
    }

    @GetMapping("/fraud/blocklist")
    public Map<String, Object> listFraudBlocklist(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        URI uri = UriComponentsBuilder.fromUriString(fraudUrl + "/admin/fraud/blocklist")
            .queryParam("page", Math.max(0, page))
            .queryParam("size", Math.min(Math.max(1, size), 100))
            .build()
            .toUri();
        var response = restTemplate.exchange(uri, HttpMethod.GET, null, OBJECT_TYPE);
        return normalizeObjectResponse("items", response.getBody());
    }

    @PostMapping("/fraud/blocklist")
    public Map<String, Object> blockFraudEntity(@RequestBody Map<String, Object> request) {
        var response = restTemplate.exchange(
            fraudUrl + "/admin/fraud/blocklist",
            HttpMethod.POST,
            jsonEntity(request != null ? request : Map.of(), null),
            MAP_TYPE
        );
        return response.getBody() != null ? response.getBody() : Map.of();
    }

    @DeleteMapping("/fraud/blocklist/{entityType}/{entityValue}")
    public Map<String, Object> unblockFraudEntity(
            @PathVariable String entityType,
            @PathVariable String entityValue) {
        String safeType = requireSafeIdentifier(entityType, "entityType");
        String safeValue = requireSafeIdentifier(entityValue, "entityValue");
        var response = restTemplate.exchange(
            fraudUrl + "/admin/fraud/blocklist/{entityType}/{entityValue}",
            HttpMethod.DELETE,
            HttpEntity.EMPTY,
            MAP_TYPE,
            safeType,
            safeValue
        );
        return response.getBody() != null
            ? response.getBody()
            : Map.of("entityType", safeType, "entityValue", safeValue, "status", "unblocked");
    }

    @GetMapping("/fraud/allowlist")
    public Map<String, Object> listFraudAllowlist() {
        var response = restTemplate.exchange(
            fraudUrl + "/admin/fraud/allowlist",
            HttpMethod.GET,
            null,
            OBJECT_TYPE
        );
        return normalizeObjectResponse("items", response.getBody());
    }

    @PostMapping("/fraud/allowlist")
    public Map<String, Object> allowFraudEntity(@RequestBody Map<String, Object> request) {
        var response = restTemplate.exchange(
            fraudUrl + "/admin/fraud/allowlist",
            HttpMethod.POST,
            jsonEntity(request != null ? request : Map.of(), null),
            MAP_TYPE
        );
        return response.getBody() != null ? response.getBody() : Map.of();
    }

    @DeleteMapping("/fraud/allowlist/{id}")
    public Map<String, Object> revokeFraudAllowlist(@PathVariable String id) {
        String safeId = requireSafeIdentifier(id, "allowlist id");
        var response = restTemplate.exchange(
            fraudUrl + "/admin/fraud/allowlist/{id}",
            HttpMethod.DELETE,
            HttpEntity.EMPTY,
            MAP_TYPE,
            safeId
        );
        return response.getBody() != null
            ? response.getBody()
            : Map.of("id", safeId, "status", "revoked");
    }

    @GetMapping("/audit/events")
    public Map<String, Object> searchAuditEvents(
            @RequestParam(required = false) String actorId,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String resourceId,
            @RequestParam(required = false) String sourceService,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String correlationId,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest httpRequest) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(auditUrl + "/admin/audit/events")
            .queryParamIfPresent("actorId", optionalText(actorId))
            .queryParamIfPresent("resourceType", optionalText(resourceType))
            .queryParamIfPresent("resourceId", optionalText(resourceId))
            .queryParamIfPresent("sourceService", optionalText(sourceService))
            .queryParamIfPresent("eventType", optionalText(eventType))
            .queryParamIfPresent("correlationId", optionalText(correlationId))
            .queryParamIfPresent("fromDate", optionalText(fromDate))
            .queryParamIfPresent("toDate", optionalText(toDate))
            .queryParam("page", Math.max(0, page))
            .queryParam("size", Math.min(Math.max(1, size), 100));
        URI uri = builder.encode().build().toUri();
        var response = restTemplate.exchange(uri, HttpMethod.GET, authForwardEntity(httpRequest), OBJECT_TYPE);
        return normalizeObjectResponse("content", response.getBody());
    }

    @GetMapping("/audit/integrity/verify")
    public Map<String, Object> verifyAuditIntegrity(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "1000") int batchSize,
            HttpServletRequest httpRequest) {
        URI uri = UriComponentsBuilder.fromUriString(auditUrl + "/admin/audit/integrity/verify")
            .queryParamIfPresent("from", optionalText(from))
            .queryParamIfPresent("to", optionalText(to))
            .queryParam("batchSize", Math.min(Math.max(1, batchSize), 5000))
            .encode()
            .build()
            .toUri();
        var response = restTemplate.exchange(uri, HttpMethod.GET, authForwardEntity(httpRequest), MAP_TYPE);
        return response.getBody() != null ? response.getBody() : Map.of();
    }

    @GetMapping("/reconciliation/pending")
    public Map<String, Object> pendingReconciliation() {
        var response = restTemplate.exchange(
            reconciliationUrl + "/pending",
            HttpMethod.GET,
            null,
            MAP_TYPE
        );
        var reconciliation = response.getBody();
        return reconciliation != null ? reconciliation : Map.of("items", List.of());
    }

    @GetMapping("/reconciliation/summary")
    public Map<String, Object> reconciliationSummary() {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
            reconciliationUrl + "/reconciliation/runs",
            HttpMethod.GET,
            HttpEntity.EMPTY,
            MAP_TYPE
        );
        Map<String, Object> body = response.getBody();
        if (body == null) {
            return Map.of("status", "ok", "count", 0);
        }
        Map<String, Object> result = new LinkedHashMap<>(body);
        result.putIfAbsent("status", "ok");
        return result;
    }

    private static String requireSafeIdentifier(String value, String fieldName) {
        if (value == null || value.isBlank() || !SAFE_IDENTIFIER.matcher(value.trim()).matches()) {
            throw new IllegalArgumentException("Invalid " + fieldName + " format");
        }
        return value.trim();
    }

    private static Optional<String> optionalText(String value) {
        return (value == null || value.isBlank()) ? Optional.empty() : Optional.of(value.trim());
    }

    private static HttpEntity<Map<String, Object>> jsonEntity(Map<String, Object> payload, HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        copyAuthorization(headers, request);
        return new HttpEntity<>(payload, headers);
    }

    private static HttpEntity<Void> authForwardEntity(HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        copyAuthorization(headers, request);
        return new HttpEntity<>(headers);
    }

    private static void copyAuthorization(HttpHeaders headers, HttpServletRequest request) {
        if (request == null) {
            return;
        }
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && !authorization.isBlank()) {
            headers.set(HttpHeaders.AUTHORIZATION, authorization);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> normalizeObjectResponse(String listKey, Object body) {
        if (body instanceof Map<?, ?> mapBody) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : mapBody.entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return result;
        }
        if (body instanceof List<?> listBody) {
            return Map.of(listKey, listBody, "count", listBody.size());
        }
        return Map.of(listKey, List.of(), "count", 0);
    }

    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null) {
            return "";
        }
        String trimmed = baseUrl.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
