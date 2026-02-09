package com.instacommerce.featureflag.service;

import com.instacommerce.featureflag.dto.response.BulkEvaluationResponse;
import com.instacommerce.featureflag.dto.response.FlagEvaluationResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class BulkEvaluationService {

    private final FlagEvaluationService flagEvaluationService;

    public BulkEvaluationService(FlagEvaluationService flagEvaluationService) {
        this.flagEvaluationService = flagEvaluationService;
    }

    public BulkEvaluationResponse evaluateAll(List<String> keys, UUID userId, Map<String, Object> context) {
        Map<String, FlagEvaluationResponse> evaluations = new LinkedHashMap<>();
        for (String key : keys) {
            evaluations.put(key, flagEvaluationService.evaluate(key, userId, context));
        }
        return new BulkEvaluationResponse(evaluations);
    }
}
