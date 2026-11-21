package com.instacommerce.admingateway.controller;

import com.instacommerce.admingateway.service.DashboardAggregationService;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

@RestController
@RequestMapping("/admin/v1")
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
public class AdminDashboardController {
    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
        new ParameterizedTypeReference<>() {};
    private static final Pattern SAFE_FLAG_ID = Pattern.compile("^[a-zA-Z0-9._:-]{1,128}$");
    private static final long MAX_OVERRIDE_TTL_SECONDS = 31_536_000L;

    private final RestTemplate restTemplate;
    private final DashboardAggregationService dashboardAggregationService;
    private final String featureFlagUrl;
    private final String reconciliationUrl;

    public AdminDashboardController(
            RestTemplate restTemplate,
            DashboardAggregationService dashboardAggregationService,
            @Value("${admin-gateway.upstream.feature-flag-url:http://config-feature-flag-service:8098}") String featureFlagUrl,
            @Value("${admin-gateway.upstream.reconciliation-url:http://reconciliation-engine:8107}") String reconciliationUrl) {
        this.restTemplate = restTemplate;
        this.dashboardAggregationService = dashboardAggregationService;
        this.featureFlagUrl = normalizeBaseUrl(featureFlagUrl);
        this.reconciliationUrl = normalizeBaseUrl(reconciliationUrl);
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        return dashboardAggregationService.aggregate();
    }

    @GetMapping("/flags")
    public Map<String, Object> featureFlags() {
        var response = restTemplate.exchange(
            featureFlagUrl + "/flags",
            HttpMethod.GET,
            null,
            MAP_TYPE
        );
        var flags = response.getBody();
        return flags != null ? flags : Map.of();
    }

    @PostMapping("/flags/{id}/override")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Map<String, Object> overrideFlag(@PathVariable String id, @RequestBody Map<String, Object> request) {
        if (id == null || id.isBlank() || !SAFE_FLAG_ID.matcher(id.trim()).matches()) {
            throw new IllegalArgumentException("Invalid flag id format");
        }
        String sanitizedId = id.trim();

        Map<String, Object> downstreamPayload = new HashMap<>(request != null ? request : Map.of());
        if (!downstreamPayload.containsKey("expiresAt")
                && downstreamPayload.get("ttlSeconds") instanceof Number ttlSeconds) {
            long ttl = ttlSeconds.longValue();
            if (ttl <= 0 || ttl > MAX_OVERRIDE_TTL_SECONDS) {
                throw new IllegalArgumentException("ttlSeconds must be between 1 and " + MAX_OVERRIDE_TTL_SECONDS);
            }
            downstreamPayload.put("expiresAt", Instant.now().plusSeconds(ttl).toString());
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(downstreamPayload, headers);
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

    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null) {
            return "";
        }
        String trimmed = baseUrl.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
