package com.instacommerce.identity.service;

import com.instacommerce.identity.repository.RefreshTokenRepository;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class RefreshTokenCleanupJob {
    private final RefreshTokenRepository refreshTokenRepository;
    private final Duration retention;

    public RefreshTokenCleanupJob(RefreshTokenRepository refreshTokenRepository,
                                  @Value("${refresh-token.cleanup-retention:7d}") Duration retention) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.retention = retention;
    }

    @Scheduled(cron = "${refresh-token.cleanup-cron:0 30 3 * * *}")
    @Transactional
    public void purgeExpiredTokens() {
        Instant cutoff = Instant.now().minus(retention);
        refreshTokenRepository.deleteExpiredAndRevokedBefore(cutoff);
    }
}
