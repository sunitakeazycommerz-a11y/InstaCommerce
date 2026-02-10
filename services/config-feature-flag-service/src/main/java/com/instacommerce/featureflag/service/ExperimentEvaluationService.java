package com.instacommerce.featureflag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.hash.Hashing;
import com.instacommerce.featureflag.domain.model.Experiment;
import com.instacommerce.featureflag.domain.model.ExperimentExposure;
import com.instacommerce.featureflag.domain.model.ExperimentStatus;
import com.instacommerce.featureflag.domain.model.ExperimentVariant;
import com.instacommerce.featureflag.dto.response.ExperimentEvaluationResponse;
import com.instacommerce.featureflag.repository.ExperimentExposureRepository;
import com.instacommerce.featureflag.repository.ExperimentRepository;
import com.instacommerce.featureflag.repository.ExperimentVariantRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ExperimentEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(ExperimentEvaluationService.class);

    private final ExperimentRepository experimentRepository;
    private final ExperimentVariantRepository variantRepository;
    private final ExperimentExposureRepository exposureRepository;
    private final ObjectMapper objectMapper;

    public ExperimentEvaluationService(ExperimentRepository experimentRepository,
                                       ExperimentVariantRepository variantRepository,
                                       ExperimentExposureRepository exposureRepository,
                                       ObjectMapper objectMapper) {
        this.experimentRepository = experimentRepository;
        this.variantRepository = variantRepository;
        this.exposureRepository = exposureRepository;
        this.objectMapper = objectMapper;
    }

    public ExperimentEvaluationResponse evaluate(String key, UUID userId, String assignmentKey,
                                                 Map<String, Object> context) {
        Experiment experiment = experimentRepository.findByKey(key).orElse(null);
        if (experiment == null) {
            return new ExperimentEvaluationResponse(key, null, null, null, null,
                    ExperimentEvaluationResponse.SOURCE_NOT_FOUND, null, null);
        }

        if (!isActive(experiment)) {
            return new ExperimentEvaluationResponse(key, experiment.getId(), null, null, null,
                    ExperimentEvaluationResponse.SOURCE_INACTIVE, null, null);
        }

        List<ExperimentVariant> variants = variantRepository.findByExperimentIdOrderByName(experiment.getId());
        if (variants.isEmpty()) {
            return new ExperimentEvaluationResponse(key, experiment.getId(), null, null, null,
                    ExperimentEvaluationResponse.SOURCE_NO_VARIANTS, null, null);
        }

        String resolvedAssignmentKey = resolveAssignmentKey(experiment, assignmentKey, userId, context);
        Long switchbackWindow = computeSwitchbackWindow(experiment, Instant.now());
        ExperimentVariant variant = assignVariant(experiment.getKey(), variants, resolvedAssignmentKey,
                switchbackWindow);
        if (variant == null) {
            return new ExperimentEvaluationResponse(key, experiment.getId(), null, null, null,
                    ExperimentEvaluationResponse.SOURCE_NO_VARIANTS, switchbackWindow, null);
        }

        Object payload = parsePayload(variant.getPayload());
        UUID exposureId = logExposure(experiment, variant, userId, resolvedAssignmentKey,
                switchbackWindow, context);

        return new ExperimentEvaluationResponse(
                key,
                experiment.getId(),
                variant.getName(),
                variant.getId(),
                payload,
                ExperimentEvaluationResponse.SOURCE_ASSIGNED,
                switchbackWindow,
                exposureId
        );
    }

    private boolean isActive(Experiment experiment) {
        if (experiment.getStatus() != ExperimentStatus.RUNNING) {
            return false;
        }
        Instant now = Instant.now();
        if (experiment.getStartAt() != null && now.isBefore(experiment.getStartAt())) {
            return false;
        }
        if (experiment.getEndAt() != null && now.isAfter(experiment.getEndAt())) {
            return false;
        }
        return true;
    }

    private String resolveAssignmentKey(Experiment experiment, String assignmentKey, UUID userId,
                                        Map<String, Object> context) {
        if (assignmentKey != null && !assignmentKey.isBlank()) {
            return assignmentKey;
        }
        if (experiment.getAssignmentUnit() != null && context != null) {
            Object value = context.get(experiment.getAssignmentUnit());
            if (value != null) {
                return String.valueOf(value);
            }
        }
        if (context != null && context.get("assignmentKey") != null) {
            return String.valueOf(context.get("assignmentKey"));
        }
        if (userId != null) {
            return userId.toString();
        }
        return "anonymous";
    }

    private Long computeSwitchbackWindow(Experiment experiment, Instant now) {
        if (!experiment.isSwitchbackEnabled()) {
            return null;
        }
        Integer intervalMinutes = experiment.getSwitchbackIntervalMinutes();
        if (intervalMinutes == null || intervalMinutes <= 0) {
            return null;
        }
        Instant start = experiment.getSwitchbackStartAt();
        if (start == null) {
            start = experiment.getStartAt();
        }
        if (start == null) {
            start = experiment.getCreatedAt();
        }
        if (start == null) {
            start = now;
        }
        long minutes = Duration.between(start, now).toMinutes();
        if (minutes < 0) {
            minutes = 0;
        }
        return minutes / intervalMinutes;
    }

    private ExperimentVariant assignVariant(String experimentKey, List<ExperimentVariant> variants,
                                            String assignmentKey, Long switchbackWindow) {
        int totalWeight = variants.stream().mapToInt(ExperimentVariant::getWeight).sum();
        if (totalWeight <= 0) {
            return null;
        }
        int bucket = computeBucket(assignmentKey, experimentKey, switchbackWindow, totalWeight);
        int cumulative = 0;
        for (ExperimentVariant variant : variants) {
            cumulative += variant.getWeight();
            if (bucket < cumulative) {
                return variant;
            }
        }
        return variants.get(variants.size() - 1);
    }

    @SuppressWarnings("deprecation")
    private int computeBucket(String assignmentKey, String experimentKey, Long switchbackWindow, int totalWeight) {
        String input = assignmentKey + ":" + experimentKey;
        if (switchbackWindow != null) {
            input += ":" + switchbackWindow;
        }
        int hash = Hashing.murmur3_32_fixed().hashString(input, StandardCharsets.UTF_8).asInt();
        return (hash & 0x7FFFFFFF) % totalWeight;
    }

    private UUID logExposure(Experiment experiment, ExperimentVariant variant, UUID userId,
                             String assignmentKey, Long switchbackWindow, Map<String, Object> context) {
        try {
            String contextJson = null;
            if (context != null && !context.isEmpty()) {
                contextJson = objectMapper.writeValueAsString(context);
            }
            ExperimentExposure exposure = new ExperimentExposure(
                    experiment.getId(),
                    variant.getId(),
                    variant.getName(),
                    userId,
                    assignmentKey,
                    switchbackWindow,
                    ExperimentEvaluationResponse.SOURCE_ASSIGNED,
                    contextJson
            );
            return exposureRepository.save(exposure).getId();
        } catch (Exception e) {
            log.warn("Failed to log exposure for experiment '{}': {}", experiment.getKey(), e.getMessage());
            return null;
        }
    }

    private Object parsePayload(String payload) {
        if (payload == null) {
            return null;
        }
        try {
            return objectMapper.readValue(payload, Object.class);
        } catch (Exception e) {
            return payload;
        }
    }
}
