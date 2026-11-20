package com.instacommerce.featureflag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.featureflag.config.FlagCacheEventListener;
import com.instacommerce.featureflag.controller.AdminFlagController;
import com.instacommerce.featureflag.controller.FlagChangeStreamController;
import com.instacommerce.featureflag.domain.model.FeatureFlag;
import com.instacommerce.featureflag.domain.model.FlagAuditLog;
import com.instacommerce.featureflag.domain.model.FlagType;
import com.instacommerce.featureflag.dto.request.AddOverrideRequest;
import com.instacommerce.featureflag.dto.request.CreateFlagRequest;
import com.instacommerce.featureflag.dto.request.UpdateFlagRequest;
import com.instacommerce.featureflag.dto.response.FlagEvaluationResponse;
import com.instacommerce.featureflag.exception.ApiException;
import com.instacommerce.featureflag.repository.FeatureFlagRepository;
import com.instacommerce.featureflag.repository.FlagAuditLogRepository;
import com.instacommerce.featureflag.repository.FlagOverrideRepository;
import java.security.Principal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cache.CacheManager;

class FlagTargetingAndRolloutBoundaryTest {

    @Test
    void validatesPercentageBoundariesTargetUsersAndTargetSegmentsAtManagementAndControllerBoundaries() {
        FeatureFlagRepository flagRepo = mock(FeatureFlagRepository.class);
        FlagAuditLogRepository auditRepo = mock(FlagAuditLogRepository.class);
        FlagChangeStreamController streamController = mock(FlagChangeStreamController.class);
        CacheManager cacheManager = mock(CacheManager.class);
        FlagCacheEventListener cacheListener = mock(FlagCacheEventListener.class);

        FlagManagementService managementService = new FlagManagementService(
            flagRepo, auditRepo, streamController, cacheManager, cacheListener);

        assertThatThrownBy(() -> managementService.createFlag(
            new CreateFlagRequest("bad key!", "name", "desc", FlagType.BOOLEAN, true, null, 0, null, null, null), "admin"))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("INVALID_FLAG_KEY"));

        assertThatThrownBy(() -> managementService.createFlag(
            new CreateFlagRequest("flag-1", "name", "desc", FlagType.PERCENTAGE, true, null, 101, null, null, null), "admin"))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("INVALID_PERCENTAGE"));

        assertThatThrownBy(() -> managementService.createFlag(
            new CreateFlagRequest("flag-1", "name", "desc", FlagType.PERCENTAGE, true, null, -1, null, null, null), "admin"))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("INVALID_PERCENTAGE"));

        assertThatThrownBy(() -> managementService.createFlag(
            new CreateFlagRequest("flag-1", "name", "desc", FlagType.USER_LIST, true, null, 0, "[\"u-1\",\"u-1\"]", null, null), "admin"))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("INVALID_TARGET_USERS"));

        assertThatThrownBy(() -> managementService.createFlag(
            new CreateFlagRequest("flag-1", "name", "desc", FlagType.BOOLEAN, true, null, 0, null, "{\"targetSegments\":[]}", null), "admin"))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("INVALID_TARGET_SEGMENTS"));

        assertThatThrownBy(() -> managementService.updateFlag(
            "flag-1",
            new UpdateFlagRequest(null, null, null, null, null, null, null, "{\"segmentRolloutPercentages\":{\"vip\":150}}"),
            "admin"))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("INVALID_PERCENTAGE"));

        FlagOverrideService overrideService = new FlagOverrideService(
            flagRepo, mock(FlagOverrideRepository.class), auditRepo, cacheListener);
        assertThatThrownBy(() -> overrideService.addOverride(
            "flag-1",
            new AddOverrideRequest(UUID.randomUUID(), "true", "expired", Instant.now().minusSeconds(60)),
            "admin"))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("INVALID_OVERRIDE_EXPIRY"));

        AdminFlagController controller = new AdminFlagController(managementService, overrideService, streamController);
        Principal principal = () -> "ops-oncall";
        assertThatThrownBy(() -> controller.setRollout("flag-1", Collections.singletonMap("percentage", null), principal))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("INVALID_PERCENTAGE"));

        FeatureFlag activeFlag = new FeatureFlag();
        activeFlag.setId(UUID.randomUUID());
        activeFlag.setKey("flag-stop");
        activeFlag.setEnabled(true);
        when(flagRepo.findByKey("flag-stop")).thenReturn(Optional.of(activeFlag));
        when(flagRepo.save(any(FeatureFlag.class))).thenAnswer(inv -> inv.getArgument(0));

        controller.emergencyStop("flag-stop", principal);

        ArgumentCaptor<FlagAuditLog> auditCaptor = ArgumentCaptor.forClass(FlagAuditLog.class);
        verify(auditRepo).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getChangedBy()).isEqualTo("ops-oncall");
    }

    @Test
    void evaluatesTargetSegmentsMatchModesAndSegmentPercentageRolloutBoundaries() {
        FlagEvaluationService evalService = new FlagEvaluationService(
            mock(FeatureFlagRepository.class), mock(FlagOverrideService.class), new ObjectMapper());
        UUID userId = UUID.randomUUID();

        FeatureFlag segmentedFlag = new FeatureFlag();
        segmentedFlag.setKey("fast-checkout");
        segmentedFlag.setFlagType(FlagType.PERCENTAGE);
        segmentedFlag.setEnabled(true);
        segmentedFlag.setRolloutPercentage(0);
        segmentedFlag.setMetadata("{\"targetSegments\":[\"vip\",\"metro-blr\"],\"segmentMatchMode\":\"ALL\","
            + "\"segmentRolloutPercentages\":{\"vip\":100}}");

        FlagEvaluationResponse noContextResp = evalService.evaluate(
            segmentedFlag, "fast-checkout", userId, Map.of(), null);
        assertThat(noContextResp.isEnabled()).isFalse();

        FlagEvaluationResponse partialSegmentResp = evalService.evaluate(
            segmentedFlag, "fast-checkout", userId, Map.of("segments", List.of("vip")), null);
        assertThat(partialSegmentResp.isEnabled()).isFalse();

        FlagEvaluationResponse fullMatchResp = evalService.evaluate(
            segmentedFlag, "fast-checkout", userId, Map.of("segments", List.of("VIP", "metro-blr")), null);
        assertThat(fullMatchResp.isEnabled()).isTrue();
        assertThat(fullMatchResp.source()).isEqualTo(FlagEvaluationResponse.SOURCE_PERCENTAGE);

        FeatureFlag zeroPctFlag = new FeatureFlag();
        zeroPctFlag.setKey("zero-pct");
        zeroPctFlag.setFlagType(FlagType.PERCENTAGE);
        zeroPctFlag.setEnabled(true);
        zeroPctFlag.setRolloutPercentage(0);
        assertThat(evalService.evaluate(zeroPctFlag, "zero-pct", userId, Map.of(), null).isEnabled()).isFalse();

        FeatureFlag fullPctFlag = new FeatureFlag();
        fullPctFlag.setKey("full-pct");
        fullPctFlag.setFlagType(FlagType.PERCENTAGE);
        fullPctFlag.setEnabled(true);
        fullPctFlag.setRolloutPercentage(100);
        assertThat(evalService.evaluate(fullPctFlag, "full-pct", userId, Map.of(), null).isEnabled()).isTrue();
    }
}
