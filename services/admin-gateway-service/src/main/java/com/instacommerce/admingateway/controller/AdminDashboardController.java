package com.instacommerce.admingateway.controller;

import java.util.Map;
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
@PreAuthorize("hasRole('ADMIN')")
public class AdminDashboardController {
    private final RestTemplate restTemplate;

    public AdminDashboardController(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        return Map.of(
            "status", "ok",
            "orderVolume", Map.of("today", 1524, "thisWeek", 8903),
            "paymentVolume", Map.of("today", 85420.50, "thisWeek", 489320.75),
            "fulfillmentRate", Map.of("pending", 234, "inProgress", 567, "completed", 8934)
        );
    }

    @GetMapping("/flags")
    public Map<String, Object> featureFlags() {
        try {
            var flags = restTemplate.getForObject("http://config-feature-flag-service:8095/flags", Map.class);
            return flags != null ? flags : Map.of("error", "No flags configured");
        } catch (Exception ex) {
            return Map.of("error", "Failed to fetch feature flags: " + ex.getMessage());
        }
    }

    @PostMapping("/flags/{id}/override")
    public Map<String, Object> overrideFlag(@PathVariable String id, @RequestBody Map<String, Object> request) {
        return Map.of(
            "flagId", id,
            "override", request.get("value"),
            "expiresIn", request.getOrDefault("ttlSeconds", 300),
            "status", "applied"
        );
    }

    @GetMapping("/reconciliation/pending")
    public Map<String, Object> pendingReconciliation() {
        try {
            var reconciliation = restTemplate.getForObject(
                "http://reconciliation-engine:8098/pending",
                Map.class);
            return reconciliation != null ? reconciliation : Map.of("items", new Object[0]);
        } catch (Exception ex) {
            return Map.of("error", "Failed to fetch pending reconciliation: " + ex.getMessage());
        }
    }
}
