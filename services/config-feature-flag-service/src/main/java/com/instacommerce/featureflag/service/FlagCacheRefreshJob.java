package com.instacommerce.featureflag.service;

import com.instacommerce.featureflag.domain.model.FeatureFlag;
import com.instacommerce.featureflag.repository.FeatureFlagRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class FlagCacheRefreshJob {

    private static final Logger log = LoggerFactory.getLogger(FlagCacheRefreshJob.class);

    private final FeatureFlagRepository flagRepository;
    private final CacheManager cacheManager;

    public FlagCacheRefreshJob(FeatureFlagRepository flagRepository, CacheManager cacheManager) {
        this.flagRepository = flagRepository;
        this.cacheManager = cacheManager;
    }

    @Scheduled(fixedRate = 30_000)
    public void refreshCache() {
        log.debug("Refreshing local feature flag cache");
        try {
            var flagsCache = cacheManager.getCache("flags");
            var allFlagsCache = cacheManager.getCache("flags-all");
            if (flagsCache == null && allFlagsCache == null) {
                return;
            }

            List<FeatureFlag> allFlags = flagRepository.findAll();
            if (flagsCache != null) {
                for (FeatureFlag flag : allFlags) {
                    flagsCache.put(flag.getKey(), flag);
                }
            }
            if (allFlagsCache != null) {
                allFlagsCache.put("all", List.copyOf(allFlags));
            }
            log.debug("Feature flag cache refreshed with {} flags", allFlags.size());
        } catch (Exception e) {
            log.error("Failed to refresh feature flag cache", e);
        }
    }
}
