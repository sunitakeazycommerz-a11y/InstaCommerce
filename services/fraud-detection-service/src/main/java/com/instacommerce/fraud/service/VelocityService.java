package com.instacommerce.fraud.service;

import com.instacommerce.fraud.repository.VelocityCounterRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VelocityService {

    private final VelocityCounterRepository velocityCounterRepository;
    private final Clock clock;

    @Autowired
    public VelocityService(VelocityCounterRepository velocityCounterRepository) {
        this(velocityCounterRepository, Clock.systemUTC());
    }

    public VelocityService(VelocityCounterRepository velocityCounterRepository, Clock clock) {
        this.velocityCounterRepository = velocityCounterRepository;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    @Transactional
    public void incrementCounter(String entityType, String entityId, String counterType) {
        incrementAmountCounter(entityType, entityId, counterType, 1L);
    }

    @Transactional
    public void incrementAmountCounter(String entityType, String entityId, String counterType, long amount) {
        if (entityType == null || entityType.isBlank()
            || entityId == null || entityId.isBlank()
            || counterType == null || counterType.isBlank()
            || amount <= 0) {
            return;
        }
        String normalizedEntityType = entityType.trim().toUpperCase(Locale.ROOT);
        String normalizedCounterType = counterType.trim().toUpperCase(Locale.ROOT);
        Instant now = Instant.now(clock);
        Duration window = windowDuration(normalizedCounterType);
        Instant windowStart = alignSubBucketStart(now, normalizedCounterType);
        Instant windowEnd = windowStart.plus(window);
        velocityCounterRepository.upsertCounter(
                normalizedEntityType, entityId.trim(), normalizedCounterType, amount, windowStart, windowEnd);
    }

    @Transactional(readOnly = true)
    public long getCount(String entityType, String entityId, String counterType) {
        if (entityType == null || entityType.isBlank()
            || entityId == null || entityId.isBlank()
            || counterType == null || counterType.isBlank()) {
            return 0L;
        }
        Instant now = Instant.now(clock);
        return velocityCounterRepository.sumCounterValueInWindow(
                entityType.trim().toUpperCase(Locale.ROOT),
                entityId.trim(),
                counterType.trim().toUpperCase(Locale.ROOT),
                now);
    }

    Instant alignSubBucketStart(Instant now, String counterType) {
        long bucketSeconds = switch (counterType) {
            case "ORDERS_5M", "FAILED_PAYMENTS_5M" -> 60L;
            case "ORDERS_1H", "FAILED_PAYMENTS_1H" -> 300L;
            case "ORDERS_24H", "AMOUNT_24H" -> 3600L;
            default -> 300L;
        };
        long epochSecond = now.getEpochSecond();
        long aligned = epochSecond - Math.floorMod(epochSecond, bucketSeconds);
        return Instant.ofEpochSecond(aligned);
    }

    private Duration windowDuration(String counterType) {
        return switch (counterType) {
            case "ORDERS_5M", "FAILED_PAYMENTS_5M" -> Duration.ofMinutes(5);
            case "ORDERS_1H", "FAILED_PAYMENTS_1H" -> Duration.ofHours(1);
            case "ORDERS_24H", "AMOUNT_24H" -> Duration.ofHours(24);
            default -> Duration.ofHours(1);
        };
    }
}
