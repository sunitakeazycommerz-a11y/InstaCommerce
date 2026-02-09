package com.instacommerce.featureflag.controller;

import com.instacommerce.featureflag.dto.request.BulkEvaluationRequest;
import com.instacommerce.featureflag.dto.response.BulkEvaluationResponse;
import com.instacommerce.featureflag.dto.response.FlagEvaluationResponse;
import com.instacommerce.featureflag.service.BulkEvaluationService;
import com.instacommerce.featureflag.service.FlagEvaluationService;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/flags")
public class FlagEvaluationController {

    private final FlagEvaluationService flagEvaluationService;
    private final BulkEvaluationService bulkEvaluationService;

    public FlagEvaluationController(FlagEvaluationService flagEvaluationService,
                                    BulkEvaluationService bulkEvaluationService) {
        this.flagEvaluationService = flagEvaluationService;
        this.bulkEvaluationService = bulkEvaluationService;
    }

    @GetMapping("/{key}")
    public ResponseEntity<FlagEvaluationResponse> evaluate(
            @PathVariable String key,
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) Map<String, Object> context) {
        FlagEvaluationResponse response = flagEvaluationService.evaluate(key, userId, context);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/bulk")
    public ResponseEntity<BulkEvaluationResponse> evaluateBulk(
            @RequestBody BulkEvaluationRequest request) {
        BulkEvaluationResponse response = bulkEvaluationService.evaluateAll(
                request.keys(), request.userId(), request.context());
        return ResponseEntity.ok(response);
    }
}
