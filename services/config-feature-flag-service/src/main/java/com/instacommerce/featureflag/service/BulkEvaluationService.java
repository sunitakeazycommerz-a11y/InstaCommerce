package com.instacommerce.featureflag.service;

import com.instacommerce.featureflag.domain.model.FeatureFlag;
import com.instacommerce.featureflag.domain.model.FlagOverride;
import com.instacommerce.featureflag.dto.response.BulkEvaluationResponse;
import com.instacommerce.featureflag.dto.response.FlagEvaluationResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class BulkEvaluationService {

    private final FlagEvaluationService flagEvaluationService;
    private final FlagOverrideService overrideService;

    public BulkEvaluationService(FlagEvaluationService flagEvaluationService,
                                 FlagOverrideService overrideService) {
        this.flagEvaluationService = flagEvaluationService;
        this.overrideService = overrideService;
    }

    public BulkEvaluationResponse evaluateAll(List<String> keys, UUID userId, Map<String, Object> context) {
        Map<String, FlagEvaluationResponse> evaluations = new LinkedHashMap<>();
        if (keys == null || keys.isEmpty()) {
            return new BulkEvaluationResponse(evaluations);
        }

        Map<String, FeatureFlag> flagsByKey = new LinkedHashMap<>();
        List<UUID> flagIds = new ArrayList<>();
        for (String key : keys) {
            FeatureFlag flag = flagEvaluationService.loadFlag(key);
            flagsByKey.put(key, flag);
            if (flag != null) {
                flagIds.add(flag.getId());
            }
        }

        Map<UUID, FlagOverride> overridesByFlagId = Map.of();
        if (userId != null && !flagIds.isEmpty()) {
            overridesByFlagId = overrideService.findActiveOverridesByFlagIds(flagIds, userId);
        }

        for (String key : keys) {
            FeatureFlag flag = flagsByKey.get(key);
            FlagOverride override = flag == null ? null : overridesByFlagId.get(flag.getId());
            evaluations.put(key, flagEvaluationService.evaluate(flag, key, userId, context, override));
        }
        return new BulkEvaluationResponse(evaluations);
    }
}
