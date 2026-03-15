package com.instacommerce.riderfleet.service;

import com.instacommerce.riderfleet.domain.model.Rider;
import com.instacommerce.riderfleet.domain.model.RiderStatus;
import com.instacommerce.riderfleet.repository.RiderAvailabilityRepository;
import com.instacommerce.riderfleet.repository.RiderRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Periodically detects riders stuck in {@link RiderStatus#ON_DELIVERY} status
 * beyond a configurable threshold and releases them back to {@link RiderStatus#ACTIVE}.
 * <p>
 * Guard-railed behind a feature flag ({@code rider-fleet.recovery.stuck-rider-enabled=true})
 * and ShedLock to guarantee single-node execution.
 */
@Component
@ConditionalOnProperty(prefix = "rider-fleet.recovery", name = "stuck-rider-enabled", havingValue = "true")
public class StuckRiderRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(StuckRiderRecoveryJob.class);

    private final RiderRepository riderRepository;
    private final RiderAvailabilityRepository availabilityRepository;
    private final OutboxService outboxService;
    private final MeterRegistry meterRegistry;
    private final int staleThresholdMinutes;
    private final int batchSize;

    public StuckRiderRecoveryJob(
            RiderRepository riderRepository,
            RiderAvailabilityRepository availabilityRepository,
            OutboxService outboxService,
            MeterRegistry meterRegistry,
            @Value("${rider-fleet.recovery.stuck-threshold-minutes:60}") int staleThresholdMinutes,
            @Value("${rider-fleet.recovery.batch-size:50}") int batchSize) {
        this.riderRepository = riderRepository;
        this.availabilityRepository = availabilityRepository;
        this.outboxService = outboxService;
        this.meterRegistry = meterRegistry;
        this.staleThresholdMinutes = staleThresholdMinutes;
        this.batchSize = batchSize;
    }

    @Scheduled(cron = "${rider-fleet.recovery.stuck-rider-cron:0 */10 * * * *}")
    @SchedulerLock(name = "stuckRiderRecovery", lockAtLeastFor = "PT5M", lockAtMostFor = "PT15M")
    public void recoverStuckRiders() {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            doRecover();
        } finally {
            sample.stop(meterRegistry.timer("rider.recovery.duration"));
        }
    }

    private void doRecover() {
        Instant cutoff = Instant.now().minusSeconds(staleThresholdMinutes * 60L);
        List<Rider> stuckRiders = riderRepository.findStuckOnDeliveryRiders(
                cutoff, PageRequest.ofSize(batchSize));

        if (stuckRiders.isEmpty()) {
            return;
        }

        log.info("Recovery: found {} stuck rider(s) older than {}", stuckRiders.size(), cutoff);
        counter("rider.recovery.found").increment(stuckRiders.size());

        for (Rider rider : stuckRiders) {
            try {
                releaseRider(rider);
                counter("rider.recovery.released").increment();
                log.info("Recovery: released stuck rider {}", rider.getId());
            } catch (Exception ex) {
                counter("rider.recovery.errors").increment();
                log.error("Recovery: failed to release rider {}", rider.getId(), ex);
            }
        }
    }

    @Retryable(retryFor = ObjectOptimisticLockingFailureException.class,
               maxAttempts = 3, backoff = @Backoff(delay = 100, multiplier = 2))
    @Transactional
    public void releaseRider(Rider rider) {
        rider.setStatus(RiderStatus.ACTIVE);
        riderRepository.save(rider);

        availabilityRepository.findByRiderId(rider.getId()).ifPresent(avail -> {
            avail.setAvailable(true);
            availabilityRepository.save(avail);
        });

        outboxService.publish("Rider", rider.getId().toString(), "RiderReleased",
                Map.of("riderId", rider.getId(),
                       "reason", "stuck_state_recovery",
                       "previousStatus", "ON_DELIVERY",
                       "releasedAt", Instant.now().toString()));
    }

    private Counter counter(String name) {
        return meterRegistry.counter(name);
    }
}
