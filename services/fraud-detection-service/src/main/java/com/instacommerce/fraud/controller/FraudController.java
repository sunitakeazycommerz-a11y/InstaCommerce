package com.instacommerce.fraud.controller;

import com.instacommerce.fraud.dto.request.FraudCheckRequest;
import com.instacommerce.fraud.dto.request.FraudReportRequest;
import com.instacommerce.fraud.dto.response.FraudCheckResponse;
import com.instacommerce.fraud.service.FraudScoringService;
import com.instacommerce.platform.security.SecurityRoleUtils;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/fraud", "/api/v1/fraud"})
public class FraudController {

    private final FraudScoringService fraudScoringService;

    public FraudController(FraudScoringService fraudScoringService) {
        this.fraudScoringService = fraudScoringService;
    }

    @PostMapping("/score")
    public FraudCheckResponse scoreTransaction(@Valid @RequestBody FraudCheckRequest request) {
        return fraudScoringService.scoreTransaction(request);
    }

    @PostMapping("/report")
    public ResponseEntity<Map<String, String>> reportSuspiciousActivity(
            @Valid @RequestBody FraudReportRequest request,
            Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equalsIgnoreCase(String.valueOf(authentication.getPrincipal()))) {
            throw new AccessDeniedException("Authentication required");
        }
        boolean privileged = SecurityRoleUtils.isPrivileged(authentication)
                || SecurityRoleUtils.hasRole(authentication, "ROLE_SUPER_ADMIN");
        UUID effectiveUserId;
        if (privileged) {
            effectiveUserId = request.userId() != null ? request.userId() : parseUuidOrNull(authentication.getName());
            if (effectiveUserId == null) {
                throw new IllegalArgumentException("userId is required");
            }
        } else {
            effectiveUserId = parseUuidOrNull(authentication.getName());
            if (effectiveUserId == null) {
                throw new AccessDeniedException("Authenticated user UUID required");
            }
        }
        FraudReportRequest boundRequest = new FraudReportRequest(
                effectiveUserId,
                request.orderId(),
                request.deviceFingerprint(),
                request.ipAddress(),
                request.reason());
        return ResponseEntity.ok(fraudScoringService.reportFraud(boundRequest));
    }

    private static UUID parseUuidOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
