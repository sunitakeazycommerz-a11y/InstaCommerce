package com.instacommerce.featureflag.controller;

import com.instacommerce.featureflag.dto.request.ExperimentEvaluationRequest;
import com.instacommerce.featureflag.dto.response.ExperimentEvaluationResponse;
import com.instacommerce.featureflag.service.ExperimentEvaluationService;
import jakarta.validation.Valid;
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
@RequestMapping("/experiments")
public class ExperimentEvaluationController {

    private final ExperimentEvaluationService experimentEvaluationService;

    public ExperimentEvaluationController(ExperimentEvaluationService experimentEvaluationService) {
        this.experimentEvaluationService = experimentEvaluationService;
    }

    @GetMapping("/{key}")
    public ResponseEntity<ExperimentEvaluationResponse> evaluate(
            @PathVariable String key,
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) String assignmentKey,
            @RequestParam(required = false) Map<String, Object> context) {
        ExperimentEvaluationResponse response = experimentEvaluationService.evaluate(key, userId, assignmentKey, context);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/evaluate")
    public ResponseEntity<ExperimentEvaluationResponse> evaluate(@Valid @RequestBody ExperimentEvaluationRequest request) {
        ExperimentEvaluationResponse response = experimentEvaluationService.evaluate(
                request.key(), request.userId(), request.assignmentKey(), request.context());
        return ResponseEntity.ok(response);
    }
}
