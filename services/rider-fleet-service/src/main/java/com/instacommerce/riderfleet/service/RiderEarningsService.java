package com.instacommerce.riderfleet.service;

import com.instacommerce.riderfleet.config.RiderFleetProperties;
import com.instacommerce.riderfleet.domain.model.Rider;
import com.instacommerce.riderfleet.domain.model.RiderEarning;
import com.instacommerce.riderfleet.dto.response.EarningsSummaryResponse;
import com.instacommerce.riderfleet.exception.RiderNotFoundException;
import com.instacommerce.riderfleet.repository.RiderEarningRepository;
import com.instacommerce.riderfleet.repository.RiderRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RiderEarningsService {
    private static final Logger logger = LoggerFactory.getLogger(RiderEarningsService.class);

    private final RiderEarningRepository earningRepository;
    private final RiderRepository riderRepository;
    private final RiderFleetProperties properties;
    private final Clock clock;

    public RiderEarningsService(RiderEarningRepository earningRepository,
                                RiderRepository riderRepository) {
        this(earningRepository, riderRepository, new RiderFleetProperties(), Clock.systemUTC());
    }

    @Autowired
    public RiderEarningsService(RiderEarningRepository earningRepository,
                                RiderRepository riderRepository,
                                RiderFleetProperties properties,
                                Clock clock) {
        this.earningRepository = earningRepository;
        this.riderRepository = riderRepository;
        this.properties = properties != null ? properties : new RiderFleetProperties();
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    @Transactional
    public void recordEarning(UUID orderId, UUID riderId, long feeCents, long tipCents) {
        recordEarning(orderId, riderId, feeCents, tipCents, 0L);
    }

    @Transactional
    public void recordEarning(UUID orderId, UUID riderId, long feeCents, long tipCents, long incentiveCents) {
        riderRepository.findById(riderId)
            .orElseThrow(() -> new RiderNotFoundException(riderId.toString()));

        Optional<RiderEarning> existing = earningRepository.findByOrderId(orderId);
        if (existing.isPresent()) {
            logger.info("Earning already recorded for order={} rider={}", orderId, riderId);
            return;
        }

        RiderEarning earning = new RiderEarning();
        earning.setRiderId(riderId);
        earning.setOrderId(orderId);
        earning.setDeliveryFeeCents(Math.max(0L, feeCents));
        earning.setTipCents(Math.max(0L, tipCents));
        earning.setIncentiveCents(Math.max(0L, incentiveCents));
        earning.setEarnedAt(Instant.now(clock));
        earningRepository.save(earning);
        logger.info("Recorded earning for rider={} order={} fee={} tip={} incentive={}",
            riderId, orderId, earning.getDeliveryFeeCents(), earning.getTipCents(), earning.getIncentiveCents());
    }

    @Transactional
    public RiderEarning recordDeliveryEarning(UUID orderId,
                                              Rider rider,
                                              Double distanceKm,
                                              Integer orderWeightGrams,
                                              long tipCents,
                                              Instant completedAt) {
        if (rider == null || rider.getId() == null) {
            throw new IllegalArgumentException("Rider is required to record delivery earning");
        }
        Optional<RiderEarning> existing = earningRepository.findByOrderId(orderId);
        if (existing.isPresent()) {
            logger.info("Dynamic earning already recorded for order={} rider={}", orderId, rider.getId());
            return existing.get();
        }

        Instant earnedAt = completedAt != null ? completedAt : Instant.now(clock);
        EarningsBreakdown breakdown = computeEarnings(rider, distanceKm, orderWeightGrams, tipCents, earnedAt);

        RiderEarning earning = new RiderEarning();
        earning.setRiderId(rider.getId());
        earning.setOrderId(orderId);
        earning.setDeliveryFeeCents(breakdown.deliveryFeeCents());
        earning.setTipCents(breakdown.tipCents());
        earning.setIncentiveCents(breakdown.incentiveCents());
        earning.setEarnedAt(earnedAt);
        RiderEarning saved = earningRepository.save(earning);
        logger.info("Recorded dynamic earning for rider={} order={} fee={} tip={} incentive={}",
            rider.getId(), orderId, breakdown.deliveryFeeCents(), breakdown.tipCents(), breakdown.incentiveCents());
        return saved;
    }

    public EarningsBreakdown computeEarnings(Rider rider,
                                             Double distanceKm,
                                             Integer orderWeightGrams,
                                             long tipCents,
                                             Instant completedAt) {
        RiderFleetProperties.Earnings cfg = properties.getEarnings();
        long fee = Math.max(0L, cfg.getBaseFeeCents());
        if (distanceKm != null && distanceKm > 0.0) {
            fee += Math.round(distanceKm * cfg.getPerKmFeeCents());
        }
        if (orderWeightGrams != null && cfg.getHeavyOrderWeightGrams() > 0
            && orderWeightGrams >= cfg.getHeavyOrderWeightGrams()) {
            fee += Math.max(0L, cfg.getHeavyOrderBonusCents());
        }
        fee = Math.max(cfg.getMinDeliveryFeeCents(), fee);

        long incentive = 0L;
        Instant evaluationTime = completedAt != null ? completedAt : Instant.now(clock);
        ZonedDateTime zoned = evaluationTime.atZone(resolveZoneId(cfg.getTimezone()));
        int hour = zoned.getHour();
        if (isPeakHour(hour)) {
            incentive += Math.max(0L, cfg.getPeakHourBonusCents());
        }
        DayOfWeek day = zoned.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            incentive += Math.max(0L, cfg.getWeekendBonusCents());
        }
        if (rider != null) {
            if (cfg.getStreakThreshold() > 0 && rider.getConsecutiveDeliveries() >= cfg.getStreakThreshold()) {
                incentive += Math.max(0L, cfg.getStreakBonusCents());
            }
            BigDecimal rating = rider.getRatingAvg();
            if (rating != null && rating.doubleValue() >= cfg.getHighRatingThreshold()) {
                incentive += Math.max(0L, cfg.getHighRatingBonusCents());
            }
        }

        long safeTip = Math.max(0L, tipCents);
        return new EarningsBreakdown(fee, safeTip, incentive);
    }

    @Transactional(readOnly = true)
    public EarningsSummaryResponse getEarningsSummary(UUID riderId, Instant fromDate, Instant toDate) {
        riderRepository.findById(riderId)
            .orElseThrow(() -> new RiderNotFoundException(riderId.toString()));

        long totalDeliveryFee = earningRepository.sumDeliveryFeeCents(riderId, fromDate, toDate);
        long totalTip = earningRepository.sumTipCents(riderId, fromDate, toDate);
        long totalIncentive = earningRepository.sumIncentiveCents(riderId, fromDate, toDate);
        long total = totalDeliveryFee + totalTip + totalIncentive;
        long count = earningRepository.countByRiderIdAndEarnedAtBetween(riderId, fromDate, toDate);

        return new EarningsSummaryResponse(
            total,
            totalDeliveryFee,
            totalTip,
            totalIncentive,
            count,
            fromDate,
            toDate
        );
    }

    private boolean isPeakHour(int hour) {
        return (hour >= 12 && hour < 14) || (hour >= 19 && hour < 22);
    }

    private ZoneId resolveZoneId(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return ZoneId.of("UTC");
        }
        try {
            return ZoneId.of(timezone.trim());
        } catch (DateTimeException ex) {
            return ZoneId.of("UTC");
        }
    }

    public record EarningsBreakdown(long deliveryFeeCents, long tipCents, long incentiveCents) {
    }
}
