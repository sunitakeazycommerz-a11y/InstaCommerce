package com.instacommerce.featureflag.service;

import com.instacommerce.featureflag.config.FlagCacheEventListener;
import com.instacommerce.featureflag.domain.model.FlagOverride;
import com.instacommerce.featureflag.repository.FeatureFlagRepository;
import com.instacommerce.featureflag.repository.FlagAuditLogRepository;
import com.instacommerce.featureflag.repository.FlagOverrideRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
}
