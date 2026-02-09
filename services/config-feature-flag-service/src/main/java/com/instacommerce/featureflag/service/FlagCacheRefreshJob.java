package com.instacommerce.featureflag.service;

import com.instacommerce.featureflag.domain.model.FeatureFlag;
import com.instacommerce.featureflag.repository.FeatureFlagRepository;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
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
    @SchedulerLock(name = "FlagCacheRefreshJob", lockAtMostFor = "25s", lockAtLeastFor = "10s")
    public void refreshCache() {
        log.debug("Refreshing feature flag cache");
        try {
            var flagsCache = cacheManager.getCache("flags");
            if (flagsCache == null) {
                return;
            }

            List<FeatureFlag> allFlags = flagRepository.findAll();
            for (FeatureFlag flag : allFlags) {
                flagsCache.put(flag.getKey(), flag);
            }
            log.debug("Feature flag cache refreshed with {} flags", allFlags.size());
        } catch (Exception e) {
            log.error("Failed to refresh feature flag cache", e);
        }
    }
}
