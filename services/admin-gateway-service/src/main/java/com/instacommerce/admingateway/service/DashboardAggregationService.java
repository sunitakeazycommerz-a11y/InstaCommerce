package com.instacommerce.admingateway.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.admingateway.config.AdminDashboardProperties;
import com.instacommerce.admingateway.config.AdminDashboardProperties.ServiceTarget;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

@Service
public class DashboardAggregationService {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
        new ParameterizedTypeReference<>() {};
    private static final TypeReference<Map<String, Object>> JACKSON_MAP_TYPE =
        new TypeReference<>() {};
    private static final Set<String> HEALTHY_STATUSES = Set.of("UP", "OK", "READY", "SERVING");
    private static final int MAX_PROMETHEUS_METRICS = 25;

    private final RestTemplate restTemplate;
    private final AdminDashboardProperties properties;
    private final ObjectMapper objectMapper;
    private final String internalServiceName;
    private final String internalServiceToken;
    private final ExecutorService executorService;

    public DashboardAggregationService(
            RestTemplate restTemplate,
            AdminDashboardProperties properties,
            ObjectMapper objectMapper,
            @Value("${internal.service.name:${spring.application.name:admin-gateway-service}}") String internalServiceName,
            @Value("${internal.service.token:}") String internalServiceToken) {
        this.restTemplate = restTemplate;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.internalServiceName = internalServiceName;
        this.internalServiceToken = internalServiceToken;
        AtomicInteger threadCounter = new AtomicInteger(1);
        this.executorService = Executors.newFixedThreadPool(8, runnable -> {
            Thread thread = new Thread(runnable, "dashboard-probe-" + threadCounter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    public void shutdown() {
        executorService.shutdownNow();
    }

    /**
     * Aggregates health and metrics across all configured downstream services within a bounded timeout,
     * marking unreachable or timed-out services {@code DEGRADED} without failing the response.
     */
    public Map<String, Object> aggregate() {
        long startNanos = System.nanoTime();
        List<ServiceTarget> targets = properties.getServices();
        HttpEntity<Void> requestEntity = new HttpEntity<>(buildInternalHeaders());
        long perServiceTimeoutMs = Math.max(100L, properties.getPerServiceTimeoutMs());
        long overallTimeoutMs = Math.max(perServiceTimeoutMs, properties.getTimeoutMs());

        List<CompletableFuture<ServiceProbe>> futures = new ArrayList<>(targets.size());
        for (ServiceTarget target : targets) {
            CompletableFuture<ServiceProbe> future = CompletableFuture
                .supplyAsync(() -> probeService(target, requestEntity), executorService)
                .orTimeout(perServiceTimeoutMs, TimeUnit.MILLISECONDS)
                .exceptionally(ex -> timeoutOrFailureProbe(target, ex));
            futures.add(future);
        }

        boolean timedOut = awaitAllBounded(futures, overallTimeoutMs);

        Map<String, Map<String, Object>> serviceDetails = new LinkedHashMap<>();
        int healthyCount = 0;
        int degradedCount = 0;
        double totalRequests = 0.0;

        for (int i = 0; i < targets.size(); i++) {
            ServiceTarget target = targets.get(i);
            CompletableFuture<ServiceProbe> future = futures.get(i);
            ServiceProbe probe = resolveCompletedProbe(target, future, overallTimeoutMs);
            serviceDetails.put(probe.name(), probe.toMap());
            if ("UP".equals(probe.status())) {
                healthyCount++;
            } else {
                degradedCount++;
            }
            if (probe.timedOut()) {
                timedOut = true;
            }
            totalRequests += probe.requestCount();
        }

        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalServices", targets.size());
        summary.put("healthyServices", healthyCount);
        summary.put("degradedServices", degradedCount);
        summary.put("totalRequests", totalRequests);
        summary.put("aggregationLatencyMs", elapsedMs);
        summary.put("timedOut", timedOut);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", degradedCount == 0 ? "ok" : "degraded");
        response.put("timestamp", Instant.now().toString());
        response.put("summary", summary);
        response.put("services", serviceDetails);
        return response;
    }

    private boolean awaitAllBounded(List<CompletableFuture<ServiceProbe>> futures, long overallTimeoutMs) {
        if (futures.isEmpty()) {
            return false;
        }
        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .get(overallTimeoutMs, TimeUnit.MILLISECONDS);
            return false;
        } catch (TimeoutException ex) {
            for (CompletableFuture<ServiceProbe> future : futures) {
                if (!future.isDone()) {
                    future.cancel(true);
                }
            }
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            for (CompletableFuture<ServiceProbe> future : futures) {
                future.cancel(true);
            }
            return true;
        } catch (ExecutionException ex) {
            return false;
        }
    }

    private ServiceProbe resolveCompletedProbe(
            ServiceTarget target,
            CompletableFuture<ServiceProbe> future,
            long overallTimeoutMs) {
        if (!future.isDone() || future.isCancelled()) {
            return ServiceProbe.degraded(
                targetName(target),
                target.getBaseUrl(),
                overallTimeoutMs,
                "Timed out after " + overallTimeoutMs + "ms",
                true);
        }
        try {
            return future.getNow(null);
        } catch (Exception ex) {
            return timeoutOrFailureProbe(target, ex);
        }
    }

    private ServiceProbe probeService(ServiceTarget target, HttpEntity<Void> requestEntity) {
        long startNanos = System.nanoTime();
        String name = targetName(target);
        String baseUrl = normalizeBaseUrl(target.getBaseUrl());
        if (baseUrl.isEmpty()) {
            return ServiceProbe.degraded(name, target.getBaseUrl(), 0L, "Missing base URL", false);
        }

        String healthUrl = baseUrl + normalizePath(target.getHealthPath(), "/actuator/health");
        String status = "DEGRADED";
        String error = null;

        try {
            ResponseEntity<Map<String, Object>> healthResponse = restTemplate.exchange(
                healthUrl,
                HttpMethod.GET,
                requestEntity,
                MAP_TYPE);
            if (healthResponse == null || !healthResponse.getStatusCode().is2xxSuccessful()) {
                error = healthResponse == null
                    ? "Empty response from downstream"
                    : "HTTP " + healthResponse.getStatusCode().value();
            } else {
                status = resolveHealthStatus(healthResponse.getBody());
                if (!"UP".equals(status)) {
                    error = "Reported downstream status: " + status;
                }
            }
        } catch (HttpStatusCodeException ex) {
            error = "HTTP " + ex.getStatusCode().value();
        } catch (Exception ex) {
            error = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
        }

        Map<String, Object> metrics = new LinkedHashMap<>();
        String metricsStatus = "UNAVAILABLE";
        String metricsPath = target.getMetricsPath();
        if (metricsPath != null && !metricsPath.isBlank()) {
            String metricsUrl = baseUrl + normalizePath(metricsPath, "/actuator/metrics/http.server.requests");
            metricsStatus = fetchMetrics(metricsUrl, requestEntity, metrics);
        }

        long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        double requestCount = extractRequestCount(metrics);
        return new ServiceProbe(name, baseUrl, status, latencyMs, metrics, metricsStatus, error, requestCount, false);
    }

    private String resolveHealthStatus(Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            return "UP";
        }
        Object rawStatus = body.get("status");
        if (rawStatus == null) {
            return "UP";
        }
        String normalized = rawStatus.toString().trim().toUpperCase(Locale.ROOT);
        return HEALTHY_STATUSES.contains(normalized) ? "UP" : "DEGRADED";
    }

    private String fetchMetrics(String metricsUrl, HttpEntity<Void> requestEntity, Map<String, Object> targetMetrics) {
        try {
            ResponseEntity<String> metricsResponse = restTemplate.exchange(
                metricsUrl,
                HttpMethod.GET,
                requestEntity,
                String.class);
            if (metricsResponse == null || !metricsResponse.getStatusCode().is2xxSuccessful()) {
                return "UNAVAILABLE";
            }
            String body = metricsResponse.getBody();
            if (body == null || body.isBlank()) {
                return "UNAVAILABLE";
            }
            String trimmed = body.trim();
            if (trimmed.startsWith("{")) {
                parseJsonMetrics(trimmed, targetMetrics);
            } else {
                parsePrometheusMetrics(trimmed, targetMetrics);
            }
            return targetMetrics.isEmpty() ? "UNAVAILABLE" : "AVAILABLE";
        } catch (Exception ex) {
            return "UNAVAILABLE";
        }
    }

    private void parseJsonMetrics(String jsonBody, Map<String, Object> targetMetrics) throws Exception {
        Map<String, Object> parsed = objectMapper.readValue(jsonBody, JACKSON_MAP_TYPE);
        Object measurementsObj = parsed.get("measurements");
        if (measurementsObj instanceof List<?> measurements) {
            for (Object item : measurements) {
                if (item instanceof Map<?, ?> measurement) {
                    Object stat = measurement.get("statistic");
                    Object val = measurement.get("value");
                    if (stat != null && val != null) {
                        targetMetrics.put(stat.toString(), val);
                    }
                }
            }
            return;
        }
        for (Map.Entry<String, Object> entry : parsed.entrySet()) {
            if (entry.getValue() instanceof Number || entry.getValue() instanceof String || entry.getValue() instanceof Boolean) {
                targetMetrics.put(entry.getKey(), entry.getValue());
            }
        }
    }

    private void parsePrometheusMetrics(String textBody, Map<String, Object> targetMetrics) {
        String[] lines = textBody.split("\\R");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int spaceIdx = lastWhitespaceIndex(trimmed);
            if (spaceIdx <= 0 || spaceIdx >= trimmed.length() - 1) {
                continue;
            }
            String metricToken = trimmed.substring(0, spaceIdx).trim();
            int labelStart = metricToken.indexOf('{');
            String metricName = labelStart > 0 ? metricToken.substring(0, labelStart) : metricToken;
            if (!targetMetrics.containsKey(metricName) && targetMetrics.size() >= MAX_PROMETHEUS_METRICS) {
                continue;
            }
            String valueToken = trimmed.substring(spaceIdx + 1).trim();
            try {
                double parsedVal = Double.parseDouble(valueToken);
                targetMetrics.merge(metricName, parsedVal,
                    (existing, added) -> ((Number) existing).doubleValue() + ((Number) added).doubleValue());
            } catch (NumberFormatException ignored) {
                continue;
            }
        }
    }

    private int lastWhitespaceIndex(String line) {
        for (int i = line.length() - 1; i >= 0; i--) {
            if (Character.isWhitespace(line.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private double extractRequestCount(Map<String, Object> metrics) {
        Object count = metrics.get("COUNT");
        if (count instanceof Number num) {
            return num.doubleValue();
        }
        Object promCount = metrics.get("http_requests_total");
        if (promCount instanceof Number num) {
            return num.doubleValue();
        }
        return 0.0;
    }

    private ServiceProbe timeoutOrFailureProbe(ServiceTarget target, Throwable throwable) {
        Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
        boolean isTimeout = cause instanceof TimeoutException;
        String message = isTimeout
            ? "Timed out after " + Math.max(100L, properties.getPerServiceTimeoutMs()) + "ms"
            : (cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName());
        return ServiceProbe.degraded(
            targetName(target),
            target.getBaseUrl(),
            isTimeout ? properties.getPerServiceTimeoutMs() : 0L,
            message,
            isTimeout);
    }

    private HttpHeaders buildInternalHeaders() {
        HttpHeaders headers = new HttpHeaders();
        if (internalServiceName != null && !internalServiceName.isBlank()) {
            headers.set("X-Internal-Service", internalServiceName);
        }
        if (internalServiceToken != null && !internalServiceToken.isBlank()) {
            headers.set("X-Internal-Token", internalServiceToken);
        }
        return headers;
    }

    private static String targetName(ServiceTarget target) {
        if (target.getName() != null && !target.getName().isBlank()) {
            return target.getName();
        }
        return target.getBaseUrl() != null ? target.getBaseUrl() : "unknown-service";
    }

    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "";
        }
        String trimmed = baseUrl.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static String normalizePath(String path, String fallback) {
        String effective = (path == null || path.isBlank()) ? fallback : path.trim();
        return effective.startsWith("/") ? effective : "/" + effective;
    }

    private record ServiceProbe(
            String name,
            String url,
            String status,
            long latencyMs,
            Map<String, Object> metrics,
            String metricsStatus,
            String error,
            double requestCount,
            boolean timedOut) {

        static ServiceProbe degraded(String name, String url, long latencyMs, String error, boolean timedOut) {
            return new ServiceProbe(name, url, "DEGRADED", latencyMs, Map.of(), "UNAVAILABLE", error, 0.0, timedOut);
        }

        Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", status);
            if (url != null) {
                result.put("url", url);
            }
            result.put("latencyMs", latencyMs);
            result.put("metricsStatus", metricsStatus);
            result.put("metrics", metrics);
            if (error != null) {
                result.put("error", error);
            }
            return result;
        }
    }
}
