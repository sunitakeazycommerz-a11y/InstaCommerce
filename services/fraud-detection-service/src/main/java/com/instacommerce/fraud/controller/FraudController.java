package com.instacommerce.fraud.controller;

import com.instacommerce.fraud.dto.request.FraudCheckRequest;
import com.instacommerce.fraud.dto.request.FraudReportRequest;
import com.instacommerce.fraud.dto.response.FraudCheckResponse;
import com.instacommerce.fraud.service.BlocklistService;
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
    private final BlocklistService blocklistService;

    public FraudController(FraudScoringService fraudScoringService, BlocklistService blocklistService) {
        this.fraudScoringService = fraudScoringService;
        this.blocklistService = blocklistService;
    }

    @PostMapping("/score")
    public FraudCheckResponse scoreTransaction(@Valid @RequestBody FraudCheckRequest request) {
        return fraudScoringService.scoreTransaction(request);
    }

    @PostMapping("/report")
    public ResponseEntity<Map<String, String>> reportSuspiciousActivity(
            @Valid @RequestBody FraudReportRequest request) {
        // Flag user for review; optionally auto-block based on repeated reports
        if (request.userId() != null) {
            blocklistService.block("USER", request.userId().toString(),
                    request.reason() != null ? request.reason() : "Reported suspicious activity",
                    null, "system:fraud-report");
        }
        return ResponseEntity.ok(Map.of("status", "reported"));
    }
}
