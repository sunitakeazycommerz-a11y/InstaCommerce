package com.instacommerce.featureflag.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.featureflag.domain.model.FeatureFlag;
import com.instacommerce.featureflag.domain.model.FlagType;
import com.instacommerce.featureflag.dto.response.FlagEvaluationResponse;
import com.instacommerce.featureflag.repository.FeatureFlagRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class FlagEvaluationServiceTest {

    private FlagEvaluationService service;

    @BeforeEach
    void setUp() {
        service = new FlagEvaluationService(
                Mockito.mock(FeatureFlagRepository.class),
                Mockito.mock(FlagOverrideService.class),
                new ObjectMapper());
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
}
