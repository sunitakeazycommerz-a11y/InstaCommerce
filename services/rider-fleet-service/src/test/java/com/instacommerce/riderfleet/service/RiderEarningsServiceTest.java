package com.instacommerce.riderfleet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.instacommerce.riderfleet.config.RiderFleetProperties;
import com.instacommerce.riderfleet.domain.model.Rider;
import com.instacommerce.riderfleet.domain.model.RiderEarning;
import com.instacommerce.riderfleet.repository.RiderEarningRepository;
import com.instacommerce.riderfleet.repository.RiderRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RiderEarningsServiceTest {

    @Mock private RiderEarningRepository earningRepository;
    @Mock private RiderRepository riderRepository;

    private RiderFleetProperties properties;
    private RiderEarningsService service;

    @BeforeEach
    void setUp() {
        properties = new RiderFleetProperties();
        properties.getEarnings().setTimezone("UTC");
        Clock fixedClock = Clock.fixed(Instant.parse("2026-03-21T13:00:00Z"), ZoneOffset.UTC);
        service = new RiderEarningsService(earningRepository, riderRepository, properties, fixedClock);
    }

    @Test
    @DisplayName("computeEarnings adds distance, heavy weight, peak hour, weekend, streak and high rating bonuses")
    void computeEarningsAppliesDynamicModifiers() {
        Rider rider = new Rider();
        rider.setId(UUID.randomUUID());
        rider.setConsecutiveDeliveries(6);
        rider.setRatingAvg(new BigDecimal("4.90"));

        // Saturday 2026-03-21T13:00:00Z -> peak hour (12..14) + weekend + streak + high rating
        Instant saturdayPeak = Instant.parse("2026-03-21T13:00:00Z");

        RiderEarningsService.EarningsBreakdown breakdown = service.computeEarnings(
            rider, 3.5, 12_000, 450L, saturdayPeak);

        // base 2500 + round(3.5 * 600 = 2100) + heavy 300 = 4900
        assertThat(breakdown.deliveryFeeCents()).isEqualTo(4900L);
        // peak 500 + weekend 250 + streak 400 + rating 300 = 1450
        assertThat(breakdown.incentiveCents()).isEqualTo(1450L);
        assertThat(breakdown.tipCents()).isEqualTo(450L);
    }

    @Test
    @DisplayName("computeEarnings omits bonuses on off-peak weekday with standard weight and clamps negative tip")
    void computeEarningsOffPeakBaseline() {
        Rider rider = new Rider();
        rider.setId(UUID.randomUUID());
        rider.setConsecutiveDeliveries(1);
        rider.setRatingAvg(new BigDecimal("4.20"));

        Instant mondayOffPeak = Instant.parse("2026-03-23T15:30:00Z");

        RiderEarningsService.EarningsBreakdown breakdown = service.computeEarnings(
            rider, null, 2_000, -100L, mondayOffPeak);

        assertThat(breakdown.deliveryFeeCents()).isEqualTo(2500L);
        assertThat(breakdown.incentiveCents()).isZero();
        assertThat(breakdown.tipCents()).isZero();
    }

    @Test
    @DisplayName("recordDeliveryEarning persists computed breakdown and skips duplicates by orderId")
    void recordDeliveryEarningPersistsAndDeduplicates() {
        UUID orderId = UUID.randomUUID();
        UUID riderId = UUID.randomUUID();
        Rider rider = new Rider();
        rider.setId(riderId);
        rider.setConsecutiveDeliveries(5);
        rider.setRatingAvg(new BigDecimal("4.80"));

        Instant peakTime = Instant.parse("2026-03-24T19:30:00Z");
        when(earningRepository.findByOrderId(orderId)).thenReturn(Optional.empty());
        when(earningRepository.save(any(RiderEarning.class))).thenAnswer(inv -> inv.getArgument(0));

        RiderEarning saved = service.recordDeliveryEarning(orderId, rider, 2.0, 5_000, 200L, peakTime);

        ArgumentCaptor<RiderEarning> captor = ArgumentCaptor.forClass(RiderEarning.class);
        verify(earningRepository).save(captor.capture());
        assertThat(saved.getDeliveryFeeCents()).isEqualTo(3700L);
        assertThat(saved.getIncentiveCents()).isEqualTo(1200L);
        assertThat(saved.getTipCents()).isEqualTo(200L);
        assertThat(saved.getEarnedAt()).isEqualTo(peakTime);

        when(earningRepository.findByOrderId(orderId)).thenReturn(Optional.of(saved));
        RiderEarning duplicate = service.recordDeliveryEarning(orderId, rider, 2.0, 5_000, 200L, peakTime);
        assertThat(duplicate).isSameAs(saved);
        verify(earningRepository, times(1)).save(any(RiderEarning.class));
    }
}
