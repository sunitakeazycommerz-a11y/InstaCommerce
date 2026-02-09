package com.instacommerce.riderfleet.service;

import com.instacommerce.riderfleet.domain.model.Rider;
import com.instacommerce.riderfleet.domain.model.RiderAvailability;
import com.instacommerce.riderfleet.domain.model.RiderStatus;
import com.instacommerce.riderfleet.exception.InvalidRiderStateException;
import com.instacommerce.riderfleet.exception.RiderNotFoundException;
import com.instacommerce.riderfleet.repository.RiderAvailabilityRepository;
import com.instacommerce.riderfleet.repository.RiderRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RiderAvailabilityService {
    private static final Logger logger = LoggerFactory.getLogger(RiderAvailabilityService.class);

    private final RiderAvailabilityRepository availabilityRepository;
    private final RiderRepository riderRepository;

    public RiderAvailabilityService(RiderAvailabilityRepository availabilityRepository,
                                     RiderRepository riderRepository) {
        this.availabilityRepository = availabilityRepository;
        this.riderRepository = riderRepository;
    }

    @Transactional
    public void updateLocation(UUID riderId, BigDecimal lat, BigDecimal lng) {
        riderRepository.findById(riderId)
            .orElseThrow(() -> new RiderNotFoundException(riderId.toString()));

        RiderAvailability availability = availabilityRepository.findByRiderId(riderId)
            .orElseThrow(() -> new RiderNotFoundException(riderId.toString()));

        availability.setCurrentLat(lat);
        availability.setCurrentLng(lng);
        availabilityRepository.save(availability);
        logger.debug("Updated location for rider id={} lat={} lng={}", riderId, lat, lng);
    }

    @Transactional
    public void toggleAvailability(UUID riderId, boolean available) {
        Rider rider = riderRepository.findById(riderId)
            .orElseThrow(() -> new RiderNotFoundException(riderId.toString()));
        if (rider.getStatus() != RiderStatus.ACTIVE) {
            throw new InvalidRiderStateException(
                "Rider must be ACTIVE to toggle availability, current: " + rider.getStatus());
        }

        RiderAvailability availability = availabilityRepository.findByRiderId(riderId)
            .orElseThrow(() -> new RiderNotFoundException(riderId.toString()));

        availability.setAvailable(available);
        availabilityRepository.save(availability);
        logger.info("Toggled availability for rider id={} available={}", riderId, available);
    }
}
