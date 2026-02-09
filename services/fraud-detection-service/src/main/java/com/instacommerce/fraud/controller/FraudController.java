package com.instacommerce.fraud.controller;

import com.instacommerce.fraud.dto.request.FraudCheckRequest;
import com.instacommerce.fraud.dto.request.FraudReportRequest;
import com.instacommerce.fraud.dto.response.FraudCheckResponse;
import com.instacommerce.fraud.service.FraudScoringService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/fraud")
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
            @Valid @RequestBody FraudReportRequest request) {
        return ResponseEntity.ok(Map.of("status", "reported"));
    }
}
