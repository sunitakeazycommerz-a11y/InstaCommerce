package com.instacommerce.fraud.service;

import com.instacommerce.fraud.domain.model.VelocityCounter;
import com.instacommerce.fraud.repository.VelocityCounterRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VelocityService {

    private final VelocityCounterRepository velocityCounterRepository;

    public VelocityService(VelocityCounterRepository velocityCounterRepository) {
        this.velocityCounterRepository = velocityCounterRepository;
    }

    @Transactional
    public void incrementCounter(String entityType, String entityId, String counterType) {
        Instant now = Instant.now();
        Duration window = windowDuration(counterType);
        Instant windowStart = now.truncatedTo(ChronoUnit.HOURS);
        Instant windowEnd = windowStart.plus(window);

        velocityCounterRepository
                .findByEntityTypeAndEntityIdAndCounterTypeAndWindowContaining(
                        entityType, entityId, counterType, now)
                .ifPresentOrElse(
                        counter -> {
                            counter.setCounterValue(counter.getCounterValue() + 1);
                            velocityCounterRepository.save(counter);
                        },
                        () -> {
                            VelocityCounter counter = new VelocityCounter();
                            counter.setEntityType(entityType);
                            counter.setEntityId(entityId);
                            counter.setCounterType(counterType);
                            counter.setCounterValue(1);
                            counter.setWindowStart(windowStart);
                            counter.setWindowEnd(windowEnd);
                            velocityCounterRepository.save(counter);
                        }
                );
    }

    @Transactional
    public void incrementAmountCounter(String entityType, String entityId, String counterType, long amount) {
        Instant now = Instant.now();
        Duration window = windowDuration(counterType);
        Instant windowStart = now.truncatedTo(ChronoUnit.HOURS);
        Instant windowEnd = windowStart.plus(window);

        velocityCounterRepository
                .findByEntityTypeAndEntityIdAndCounterTypeAndWindowContaining(
                        entityType, entityId, counterType, now)
                .ifPresentOrElse(
                        counter -> {
                            counter.setCounterValue(counter.getCounterValue() + amount);
                            velocityCounterRepository.save(counter);
                        },
                        () -> {
                            VelocityCounter counter = new VelocityCounter();
                            counter.setEntityType(entityType);
                            counter.setEntityId(entityId);
                            counter.setCounterType(counterType);
                            counter.setCounterValue(amount);
                            counter.setWindowStart(windowStart);
                            counter.setWindowEnd(windowEnd);
                            velocityCounterRepository.save(counter);
                        }
                );
    }

    @Transactional(readOnly = true)
    public long getCount(String entityType, String entityId, String counterType) {
        Instant now = Instant.now();
        return velocityCounterRepository
                .findByEntityTypeAndEntityIdAndCounterTypeAndWindowContaining(
                        entityType, entityId, counterType, now)
                .map(VelocityCounter::getCounterValue)
                .orElse(0L);
    }

    private Duration windowDuration(String counterType) {
        return switch (counterType) {
            case "ORDERS_1H", "FAILED_PAYMENTS_1H" -> Duration.ofHours(1);
            case "ORDERS_24H", "AMOUNT_24H" -> Duration.ofHours(24);
            default -> Duration.ofHours(1);
        };
    }
}
