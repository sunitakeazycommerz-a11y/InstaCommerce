package com.instacommerce.search.service;

import com.instacommerce.search.domain.model.TrendingQuery;
import com.instacommerce.search.repository.TrendingQueryRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TrendingService {
    private static final Logger log = LoggerFactory.getLogger(TrendingService.class);

    private final TrendingQueryRepository trendingQueryRepository;

    public TrendingService(TrendingQueryRepository trendingQueryRepository) {
        this.trendingQueryRepository = trendingQueryRepository;
    }

    @Async
    @Transactional
    public void recordQuery(String query) {
        if (query == null || query.isBlank()) {
            return;
        }
        String normalized = query.trim().toLowerCase();
        trendingQueryRepository.upsertHit(normalized);
    }

    @Cacheable(value = "trending", key = "#limit")
    public List<String> getTrending(int limit) {
        return trendingQueryRepository.findTopByOrderByHitCountDesc(PageRequest.of(0, limit)).stream()
                .map(TrendingQuery::getQuery)
                .collect(Collectors.toList());
    }

    @Scheduled(cron = "0 0 3 * * *")
    @SchedulerLock(name = "cleanupTrendingQueries", lockAtLeastFor = "5m", lockAtMostFor = "30m")
    @Transactional
    @CacheEvict(value = "trending", allEntries = true)
    public void cleanupOldEntries() {
        Instant cutoff = Instant.now().minus(30, ChronoUnit.DAYS);
        int deleted = trendingQueryRepository.deleteOlderThan(cutoff);
        log.info("Cleaned up {} trending queries older than {}", deleted, cutoff);
    }
}
