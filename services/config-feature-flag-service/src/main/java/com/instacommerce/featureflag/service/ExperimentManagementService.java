package com.instacommerce.featureflag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.featureflag.domain.model.Experiment;
import com.instacommerce.featureflag.domain.model.ExperimentVariant;
import com.instacommerce.featureflag.dto.request.CreateExperimentRequest;
import com.instacommerce.featureflag.dto.request.ExperimentVariantRequest;
import com.instacommerce.featureflag.dto.request.UpdateExperimentRequest;
import com.instacommerce.featureflag.dto.response.ExperimentResponse;
import com.instacommerce.featureflag.dto.response.ExperimentVariantResponse;
import com.instacommerce.featureflag.exception.ApiException;
import com.instacommerce.featureflag.repository.ExperimentRepository;
import com.instacommerce.featureflag.repository.ExperimentVariantRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExperimentManagementService {

    private final ExperimentRepository experimentRepository;
    private final ExperimentVariantRepository variantRepository;
    private final ObjectMapper objectMapper;

    public ExperimentManagementService(ExperimentRepository experimentRepository,
                                       ExperimentVariantRepository variantRepository,
                                       ObjectMapper objectMapper) {
        this.experimentRepository = experimentRepository;
        this.variantRepository = variantRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ExperimentResponse createExperiment(CreateExperimentRequest request, String changedBy) {
        if (experimentRepository.findByKey(request.key()).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "EXPERIMENT_ALREADY_EXISTS",
                    "Experiment with key '" + request.key() + "' already exists");
        }
        validateDates(request.startAt(), request.endAt());
        validateSwitchback(request.switchbackEnabled(), request.switchbackIntervalMinutes());
        validateVariants(request.variants());

        Experiment experiment = new Experiment();
        experiment.setKey(request.key());
        experiment.setName(request.name());
        experiment.setDescription(request.description());
        if (request.status() != null) {
            experiment.setStatus(request.status());
        }
        experiment.setAssignmentUnit(request.assignmentUnit());
        experiment.setStartAt(request.startAt());
        experiment.setEndAt(request.endAt());
        if (request.switchbackEnabled() != null) {
            experiment.setSwitchbackEnabled(request.switchbackEnabled());
        }
        experiment.setSwitchbackIntervalMinutes(request.switchbackIntervalMinutes());
        experiment.setSwitchbackStartAt(request.switchbackStartAt());
        experiment.setMetadata(request.metadata());
        experiment.setCreatedBy(changedBy);

        experiment = experimentRepository.save(experiment);
        List<ExperimentVariant> variants = buildVariants(experiment.getId(), request.variants());
        variantRepository.saveAll(variants);

        return toResponse(experiment, variants);
    }

    @Transactional
    public ExperimentResponse updateExperiment(String key, UpdateExperimentRequest request, String changedBy) {
        Experiment experiment = findByKeyOrThrow(key);
        validateDates(request.startAt() != null ? request.startAt() : experiment.getStartAt(),
                request.endAt() != null ? request.endAt() : experiment.getEndAt());
        validateSwitchback(request.switchbackEnabled() != null ? request.switchbackEnabled()
                : experiment.isSwitchbackEnabled(),
                request.switchbackIntervalMinutes() != null ? request.switchbackIntervalMinutes()
                        : experiment.getSwitchbackIntervalMinutes());

        if (request.name() != null) {
            experiment.setName(request.name());
        }
        if (request.description() != null) {
            experiment.setDescription(request.description());
        }
        if (request.status() != null) {
            experiment.setStatus(request.status());
        }
        if (request.assignmentUnit() != null) {
            experiment.setAssignmentUnit(request.assignmentUnit());
        }
        if (request.startAt() != null) {
            experiment.setStartAt(request.startAt());
        }
        if (request.endAt() != null) {
            experiment.setEndAt(request.endAt());
        }
        if (request.switchbackEnabled() != null) {
            experiment.setSwitchbackEnabled(request.switchbackEnabled());
        }
        if (request.switchbackIntervalMinutes() != null) {
            experiment.setSwitchbackIntervalMinutes(request.switchbackIntervalMinutes());
        }
        if (request.switchbackStartAt() != null) {
            experiment.setSwitchbackStartAt(request.switchbackStartAt());
        }
        if (request.metadata() != null) {
            experiment.setMetadata(request.metadata());
        }
        experiment = experimentRepository.save(experiment);

        List<ExperimentVariant> variants;
        if (request.variants() != null) {
            validateVariants(request.variants());
            variantRepository.deleteByExperimentId(experiment.getId());
            variants = buildVariants(experiment.getId(), request.variants());
            variantRepository.saveAll(variants);
        } else {
            variants = variantRepository.findByExperimentIdOrderByName(experiment.getId());
        }

        return toResponse(experiment, variants);
    }

    public ExperimentResponse getExperiment(String key) {
        Experiment experiment = findByKeyOrThrow(key);
        List<ExperimentVariant> variants = variantRepository.findByExperimentIdOrderByName(experiment.getId());
        return toResponse(experiment, variants);
    }

    public List<ExperimentResponse> getAllExperiments() {
        return experimentRepository.findAll().stream()
                .map(experiment -> {
                    List<ExperimentVariant> variants = variantRepository
                            .findByExperimentIdOrderByName(experiment.getId());
                    return toResponse(experiment, variants);
                })
                .collect(Collectors.toList());
    }

    private Experiment findByKeyOrThrow(String key) {
        return experimentRepository.findByKey(key)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "EXPERIMENT_NOT_FOUND",
                        "Experiment with key '" + key + "' not found"));
    }

    private void validateVariants(List<ExperimentVariantRequest> variants) {
        if (variants == null || variants.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VARIANTS_REQUIRED",
                    "At least one experiment variant is required");
        }
        Set<String> names = new HashSet<>();
        int totalWeight = 0;
        for (ExperimentVariantRequest variant : variants) {
            String name = variant.name() == null ? "" : variant.name().trim().toLowerCase();
            if (!names.add(name)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "DUPLICATE_VARIANT",
                        "Duplicate variant name '" + variant.name() + "'");
            }
            totalWeight += variant.weight() == null ? 0 : variant.weight();
        }
        if (totalWeight <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_WEIGHTS",
                    "Total variant weight must be greater than zero");
        }
    }

    private void validateSwitchback(Boolean enabled, Integer intervalMinutes) {
        if (Boolean.TRUE.equals(enabled) && (intervalMinutes == null || intervalMinutes <= 0)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SWITCHBACK_INTERVAL",
                    "Switchback interval minutes must be provided for switchback experiments");
        }
    }

    private void validateDates(Instant startAt, Instant endAt) {
        if (startAt != null && endAt != null && endAt.isBefore(startAt)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_EXPERIMENT_WINDOW",
                    "Experiment end time must be after start time");
        }
    }

    private List<ExperimentVariant> buildVariants(UUID experimentId, List<ExperimentVariantRequest> requests) {
        List<ExperimentVariant> variants = new ArrayList<>();
        for (ExperimentVariantRequest request : requests) {
            ExperimentVariant variant = new ExperimentVariant();
            variant.setExperimentId(experimentId);
            variant.setName(request.name());
            if (request.weight() != null) {
                variant.setWeight(request.weight());
            }
            if (request.payload() != null) {
                variant.setPayload(request.payload());
            }
            if (request.control() != null) {
                variant.setControl(request.control());
            }
            variants.add(variant);
        }
        return variants;
    }

    private ExperimentResponse toResponse(Experiment experiment, List<ExperimentVariant> variants) {
        List<ExperimentVariantResponse> variantResponses = variants.stream()
                .map(variant -> new ExperimentVariantResponse(
                        variant.getId(),
                        variant.getName(),
                        variant.getWeight(),
                        parsePayload(variant.getPayload()),
                        variant.isControl()))
                .collect(Collectors.toList());
        return new ExperimentResponse(
                experiment.getId(),
                experiment.getKey(),
                experiment.getName(),
                experiment.getDescription(),
                experiment.getStatus(),
                experiment.getAssignmentUnit(),
                experiment.getStartAt(),
                experiment.getEndAt(),
                experiment.isSwitchbackEnabled(),
                experiment.getSwitchbackIntervalMinutes(),
                experiment.getSwitchbackStartAt(),
                experiment.getMetadata(),
                experiment.getCreatedBy(),
                experiment.getCreatedAt(),
                experiment.getUpdatedAt(),
                experiment.getVersion(),
                variantResponses
        );
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
