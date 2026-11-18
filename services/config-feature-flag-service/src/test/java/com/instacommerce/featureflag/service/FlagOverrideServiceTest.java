package com.instacommerce.featureflag.service;

import com.instacommerce.featureflag.config.FlagCacheEventListener;
import com.instacommerce.featureflag.domain.model.FeatureFlag;
import com.instacommerce.featureflag.domain.model.FlagOverride;
import com.instacommerce.featureflag.dto.request.AddOverrideRequest;
import com.instacommerce.featureflag.repository.FeatureFlagRepository;
import com.instacommerce.featureflag.repository.FlagAuditLogRepository;
import com.instacommerce.featureflag.repository.FlagOverrideRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FlagOverrideServiceTest {

    private FeatureFlagRepository flagRepository;
    private FlagOverrideRepository overrideRepository;
    private FlagAuditLogRepository auditLogRepository;
    private FlagCacheEventListener cacheEventListener;
    private FlagOverrideService service;

    @BeforeEach
    void setUp() {
        flagRepository = Mockito.mock(FeatureFlagRepository.class);
        overrideRepository = Mockito.mock(FlagOverrideRepository.class);
        auditLogRepository = Mockito.mock(FlagAuditLogRepository.class);
        cacheEventListener = Mockito.mock(FlagCacheEventListener.class);
        service = new FlagOverrideService(flagRepository, overrideRepository, auditLogRepository, cacheEventListener);
    }

    @Test
    void bulkCacheKey_formatsAndSortsDeterministically() {
        UUID u1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID u2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID user = UUID.fromString("00000000-0000-0000-0000-000000000099");

        // Regardless of insertion order, key must be identical
        String key1 = service.bulkCacheKey(List.of(u2, u1), user);
        String key2 = service.bulkCacheKey(List.of(u1, u2), user);

        assertThat(key1).isEqualTo(key2);
        assertThat(key1).isEqualTo(u1 + "," + u2 + ":" + user);
    }

    @Test
    void bulkCacheKey_handlesEmptyAndNull() {
        UUID user = UUID.randomUUID();
        assertThat(service.bulkCacheKey(List.of(), user)).isEqualTo(":" + user);
        assertThat(service.bulkCacheKey(null, user)).isEqualTo(":" + user);
    }

    @Test
    void findActiveOverridesByFlagIds_emptyReturnsEmptyMap() {
        Map<UUID, FlagOverride> result = service.findActiveOverridesByFlagIds(List.of(), UUID.randomUUID());
        assertThat(result).isEmpty();
    }

    @Test
    void addOverride_updatesExistingOverrideInPlaceWithoutDelete() {
        UUID flagId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID existingId = UUID.randomUUID();

        FeatureFlag flag = new FeatureFlag();
        flag.setId(flagId);
        flag.setKey("checkout-v2");

        FlagOverride existing = new FlagOverride();
        existing.setId(existingId);
        existing.setFlagId(flagId);
        existing.setUserId(userId);
        existing.setOverrideValue("false");

        when(flagRepository.findByKey("checkout-v2")).thenReturn(Optional.of(flag));
        when(overrideRepository.findByFlagIdAndUserId(flagId, userId)).thenReturn(Optional.of(existing));
        when(overrideRepository.save(any(FlagOverride.class))).thenAnswer(inv -> inv.getArgument(0));

        AddOverrideRequest request = new AddOverrideRequest(userId, "true", "QA test", Instant.now().plusSeconds(3600));
        FlagOverride saved = service.addOverride("checkout-v2", request, "admin@instacommerce.dev");

        assertThat(saved.getId()).isEqualTo(existingId);
        assertThat(saved.getOverrideValue()).isEqualTo("true");
        assertThat(saved.getReason()).isEqualTo("QA test");
        verify(overrideRepository, never()).delete(any());
        verify(overrideRepository).save(existing);
    }
}
