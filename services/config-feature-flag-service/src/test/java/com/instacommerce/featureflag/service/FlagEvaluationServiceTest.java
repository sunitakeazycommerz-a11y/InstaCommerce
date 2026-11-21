package com.instacommerce.featureflag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.featureflag.domain.model.FeatureFlag;
import com.instacommerce.featureflag.domain.model.FlagType;
import com.instacommerce.featureflag.dto.response.FlagEvaluationResponse;
import com.instacommerce.featureflag.repository.FeatureFlagRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class FlagEvaluationServiceTest {

    private ObjectMapper objectMapper;
    private FlagEvaluationService service;

    @BeforeEach
    void setUp() {
        objectMapper = spy(new ObjectMapper());
        service = new FlagEvaluationService(
                Mockito.mock(FeatureFlagRepository.class),
                Mockito.mock(FlagOverrideService.class),
                objectMapper);
    }

    @Test
    void evaluate_jsonFlagDisabled_returnsFalse() {
        FeatureFlag flag = new FeatureFlag();
        flag.setKey("checkout-config");
        flag.setFlagType(FlagType.JSON);
        flag.setEnabled(false);
        flag.setDefaultValue("{\"minOrder\":100}");

        FlagEvaluationResponse response = service.evaluate(flag, "checkout-config", UUID.randomUUID(), Map.of(), null);

        assertThat(response.value()).isEqualTo(false);
        assertThat(response.isEnabled()).isFalse();
    }

    @Test
    void evaluate_jsonFlagEnabled_returnsParsedDefaultValue() {
        FeatureFlag flag = new FeatureFlag();
        flag.setKey("checkout-config");
        flag.setFlagType(FlagType.JSON);
        flag.setEnabled(true);
        flag.setDefaultValue("{\"minOrder\":100}");

        FlagEvaluationResponse response = service.evaluate(flag, "checkout-config", UUID.randomUUID(), Map.of(), null);

        assertThat(response.isEnabled()).isTrue();
        assertThat(response.value()).isInstanceOf(Map.class);
    }

    @Test
    void evaluate_corruptSegmentMetadataJson_failsClosed() {
        FeatureFlag flag = new FeatureFlag();
        flag.setKey("vip-checkout");
        flag.setFlagType(FlagType.BOOLEAN);
        flag.setEnabled(true);
        flag.setMetadata("{corrupt-json");

        FlagEvaluationResponse response = service.evaluate(
                flag, "vip-checkout", UUID.randomUUID(), Map.of("segment", "vip"), null);

        assertThat(response.isEnabled()).isFalse();
        assertThat(response.source()).isEqualTo(FlagEvaluationResponse.SOURCE_DEFAULT);
    }

    @Test
    void evaluate_nonObjectSegmentMetadataJson_failsClosed() {
        FeatureFlag flag = new FeatureFlag();
        flag.setKey("vip-checkout");
        flag.setFlagType(FlagType.BOOLEAN);
        flag.setEnabled(true);
        flag.setMetadata("[\"vip\"]");

        FlagEvaluationResponse response = service.evaluate(
                flag, "vip-checkout", UUID.randomUUID(), Map.of("segment", "vip"), null);

        assertThat(response.isEnabled()).isFalse();
    }

    @Test
    void evaluate_normalizesSegmentRolloutKeysAndCachesParsedMetadata() throws Exception {
        FeatureFlag flag = new FeatureFlag();
        flag.setKey("tiered-rollout");
        flag.setFlagType(FlagType.PERCENTAGE);
        flag.setEnabled(true);
        flag.setRolloutPercentage(0);
        flag.setMetadata("{\"targetSegments\":[\"VIP\"],\"segmentRolloutPercentages\":{\"vip\":100}}");

        UUID userId = UUID.randomUUID();
        FlagEvaluationResponse first = service.evaluate(
                flag, "tiered-rollout", userId, Map.of("segments", List.of("vip")), null);
        FlagEvaluationResponse second = service.evaluate(
                flag, "tiered-rollout", userId, Map.of("segments", List.of("ViP")), null);

        assertThat(first.isEnabled()).isTrue();
        assertThat(second.isEnabled()).isTrue();
        verify(objectMapper, times(1)).readTree(anyString());
    }
}
