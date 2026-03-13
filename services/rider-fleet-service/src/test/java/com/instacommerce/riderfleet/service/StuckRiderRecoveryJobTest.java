package com.instacommerce.riderfleet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.instacommerce.riderfleet.domain.model.Rider;
import com.instacommerce.riderfleet.domain.model.RiderAvailability;
import com.instacommerce.riderfleet.domain.model.RiderStatus;
import com.instacommerce.riderfleet.repository.RiderAvailabilityRepository;
import com.instacommerce.riderfleet.repository.RiderRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StuckRiderRecoveryJobTest {

    @Mock private RiderRepository riderRepository;
    @Mock private RiderAvailabilityRepository availabilityRepository;
    @Mock private OutboxService outboxService;
    private SimpleMeterRegistry meterRegistry;
    private StuckRiderRecoveryJob job;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        job = new StuckRiderRecoveryJob(
                riderRepository, availabilityRepository, outboxService,
                meterRegistry, 60, 50);
    }

    @Test
    void noStuckRiders_doesNothing() {
        when(riderRepository.findStuckOnDeliveryRiders(any(Instant.class), any()))
                .thenReturn(Collections.emptyList());
        job.recoverStuckRiders();
        verify(riderRepository, never()).save(any());
    }

    @Test
    void stuckRider_releasedToActive() {
        Rider rider = new Rider();
        rider.setId(UUID.randomUUID());
        rider.setStatus(RiderStatus.ON_DELIVERY);

        RiderAvailability avail = new RiderAvailability();
        avail.setAvailable(false);

        when(riderRepository.findStuckOnDeliveryRiders(any(Instant.class), any()))
                .thenReturn(List.of(rider));
        when(availabilityRepository.findByRiderId(rider.getId()))
                .thenReturn(Optional.of(avail));

        job.recoverStuckRiders();

        assertThat(rider.getStatus()).isEqualTo(RiderStatus.ACTIVE);
        assertThat(avail.isAvailable()).isTrue();
        verify(riderRepository).save(rider);
        verify(availabilityRepository).save(avail);
        verify(outboxService).publish(eq("Rider"), eq(rider.getId().toString()),
                eq("RiderReleased"), any());
        assertThat(meterRegistry.counter("rider.recovery.released").count()).isEqualTo(1.0);
    }

    @Test
    void riderWithNoAvailability_stillReleasesStatus() {
        Rider rider = new Rider();
        rider.setId(UUID.randomUUID());
        rider.setStatus(RiderStatus.ON_DELIVERY);

        when(riderRepository.findStuckOnDeliveryRiders(any(Instant.class), any()))
                .thenReturn(List.of(rider));
        when(availabilityRepository.findByRiderId(rider.getId()))
                .thenReturn(Optional.empty());

        job.recoverStuckRiders();

        assertThat(rider.getStatus()).isEqualTo(RiderStatus.ACTIVE);
        verify(riderRepository).save(rider);
        verify(availabilityRepository, never()).save(any());
    }

    @Test
    void errorOnOneRider_otherStillProcessed() {
        Rider good = new Rider();
        good.setId(UUID.randomUUID());
        good.setStatus(RiderStatus.ON_DELIVERY);

        Rider bad = new Rider();
        bad.setId(UUID.randomUUID());
        bad.setStatus(RiderStatus.ON_DELIVERY);

        when(riderRepository.findStuckOnDeliveryRiders(any(Instant.class), any()))
                .thenReturn(List.of(bad, good));
        when(availabilityRepository.findByRiderId(bad.getId()))
                .thenThrow(new RuntimeException("DB error"));
        when(availabilityRepository.findByRiderId(good.getId()))
                .thenReturn(Optional.empty());

        job.recoverStuckRiders();

        assertThat(good.getStatus()).isEqualTo(RiderStatus.ACTIVE);
        assertThat(meterRegistry.counter("rider.recovery.errors").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("rider.recovery.released").count()).isEqualTo(1.0);
    }
}
