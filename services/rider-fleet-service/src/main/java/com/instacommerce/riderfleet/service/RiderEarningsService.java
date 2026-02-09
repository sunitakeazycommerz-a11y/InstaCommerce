package com.instacommerce.riderfleet.service;

import com.instacommerce.riderfleet.domain.model.RiderEarning;
import com.instacommerce.riderfleet.dto.response.EarningsSummaryResponse;
import com.instacommerce.riderfleet.exception.RiderNotFoundException;
import com.instacommerce.riderfleet.repository.RiderEarningRepository;
import com.instacommerce.riderfleet.repository.RiderRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RiderEarningsService {
    private static final Logger logger = LoggerFactory.getLogger(RiderEarningsService.class);

    private final RiderEarningRepository earningRepository;
    private final RiderRepository riderRepository;

    public RiderEarningsService(RiderEarningRepository earningRepository,
                                 RiderRepository riderRepository) {
        this.earningRepository = earningRepository;
        this.riderRepository = riderRepository;
    }

    @Transactional
    public void recordEarning(UUID orderId, UUID riderId, long feeCents, long tipCents) {
        riderRepository.findById(riderId)
            .orElseThrow(() -> new RiderNotFoundException(riderId.toString()));

        RiderEarning earning = new RiderEarning();
        earning.setRiderId(riderId);
        earning.setOrderId(orderId);
        earning.setDeliveryFeeCents(feeCents);
        earning.setTipCents(tipCents);
        earningRepository.save(earning);
        logger.info("Recorded earning for rider={} order={} fee={} tip={}", riderId, orderId, feeCents, tipCents);
    }

    @Transactional(readOnly = true)
    public EarningsSummaryResponse getEarningsSummary(UUID riderId, Instant fromDate, Instant toDate) {
        riderRepository.findById(riderId)
            .orElseThrow(() -> new RiderNotFoundException(riderId.toString()));

        List<RiderEarning> earnings = earningRepository.findByRiderIdAndEarnedAtBetween(riderId, fromDate, toDate);

        long totalDeliveryFee = earnings.stream().mapToLong(RiderEarning::getDeliveryFeeCents).sum();
        long totalTip = earnings.stream().mapToLong(RiderEarning::getTipCents).sum();
        long totalIncentive = earnings.stream().mapToLong(RiderEarning::getIncentiveCents).sum();
        long total = totalDeliveryFee + totalTip + totalIncentive;

        return new EarningsSummaryResponse(
            total,
            totalDeliveryFee,
            totalTip,
            totalIncentive,
            earnings.size(),
            fromDate,
            toDate
        );
    }
}
