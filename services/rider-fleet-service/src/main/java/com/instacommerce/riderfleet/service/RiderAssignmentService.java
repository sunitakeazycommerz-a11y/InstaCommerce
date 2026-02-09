package com.instacommerce.riderfleet.service;

import com.instacommerce.riderfleet.config.RiderFleetProperties;
import com.instacommerce.riderfleet.domain.model.Rider;
import com.instacommerce.riderfleet.domain.model.RiderAvailability;
import com.instacommerce.riderfleet.domain.model.RiderStatus;
import com.instacommerce.riderfleet.exception.NoAvailableRiderException;
import com.instacommerce.riderfleet.exception.RiderNotFoundException;
import com.instacommerce.riderfleet.repository.RiderAvailabilityRepository;
import com.instacommerce.riderfleet.repository.RiderRepository;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RiderAssignmentService {
    private static final Logger logger = LoggerFactory.getLogger(RiderAssignmentService.class);

    private final RiderRepository riderRepository;
    private final RiderAvailabilityRepository availabilityRepository;
    private final OutboxService outboxService;
    private final RiderFleetProperties properties;

    public RiderAssignmentService(RiderRepository riderRepository,
                                   RiderAvailabilityRepository availabilityRepository,
                                   OutboxService outboxService,
                                   RiderFleetProperties properties) {
        this.riderRepository = riderRepository;
        this.availabilityRepository = availabilityRepository;
        this.outboxService = outboxService;
        this.properties = properties;
    }

    /**
     * Assigns the nearest available rider to an order.
     * Uses pessimistic locking (FOR UPDATE SKIP LOCKED) via the repository query
     * to prevent double-assignment in concurrent scenarios.
     */
    @Transactional
    public UUID assignRider(UUID orderId, UUID storeId, BigDecimal pickupLat, BigDecimal pickupLng) {
        double radiusKm = properties.getAssignment().getDefaultRadiusKm();

        RiderAvailability availability = availabilityRepository
            .findNearestAvailable(pickupLat, pickupLng, radiusKm, storeId)
            .orElseThrow(() -> new NoAvailableRiderException(storeId.toString()));

        Rider rider = riderRepository.findById(availability.getRiderId())
            .orElseThrow(() -> new RiderNotFoundException(availability.getRiderId().toString()));

        rider.setStatus(RiderStatus.ON_DELIVERY);
        riderRepository.save(rider);

        availability.setAvailable(false);
        availabilityRepository.save(availability);

        outboxService.publish("Rider", rider.getId().toString(), "RiderAssigned",
            Map.of("riderId", rider.getId(), "orderId", orderId, "storeId", storeId));

        logger.info("Assigned rider id={} to order={} store={}", rider.getId(), orderId, storeId);
        return rider.getId();
    }
}
