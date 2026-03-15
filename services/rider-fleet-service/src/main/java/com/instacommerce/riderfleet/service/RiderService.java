package com.instacommerce.riderfleet.service;

import com.instacommerce.riderfleet.domain.model.Rider;
import com.instacommerce.riderfleet.domain.model.RiderAvailability;
import com.instacommerce.riderfleet.domain.model.RiderStatus;
import com.instacommerce.riderfleet.dto.request.CreateRiderRequest;
import com.instacommerce.riderfleet.dto.response.RiderResponse;
import com.instacommerce.riderfleet.exception.InvalidRiderStateException;
import com.instacommerce.riderfleet.exception.RiderNotFoundException;
import com.instacommerce.riderfleet.repository.RiderAvailabilityRepository;
import com.instacommerce.riderfleet.repository.RiderRepository;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RiderService {
    private static final Logger logger = LoggerFactory.getLogger(RiderService.class);

    private final RiderRepository riderRepository;
    private final RiderAvailabilityRepository availabilityRepository;
    private final OutboxService outboxService;

    public RiderService(RiderRepository riderRepository,
                        RiderAvailabilityRepository availabilityRepository,
                        OutboxService outboxService) {
        this.riderRepository = riderRepository;
        this.availabilityRepository = availabilityRepository;
        this.outboxService = outboxService;
    }

    @Transactional
    public RiderResponse createRider(CreateRiderRequest request) {
        Rider rider = new Rider();
        rider.setName(request.name());
        rider.setPhone(request.phone());
        rider.setEmail(request.email());
        rider.setVehicleType(request.vehicleType());
        rider.setLicenseNumber(request.licenseNumber());
        rider.setStoreId(request.storeId());
        rider.setStatus(RiderStatus.INACTIVE);
        rider = riderRepository.save(rider);

        RiderAvailability availability = new RiderAvailability();
        availability.setRiderId(rider.getId());
        availability.setAvailable(false);
        availability.setStoreId(rider.getStoreId());
        availabilityRepository.save(availability);

        outboxService.publish("Rider", rider.getId().toString(), "RiderCreated", toResponse(rider));
        logger.info("Created rider id={} phone={}", rider.getId(), rider.getPhone());
        return toResponse(rider);
    }

    @Transactional(readOnly = true)
    public RiderResponse getRider(UUID riderId) {
        Rider rider = riderRepository.findById(riderId)
            .orElseThrow(() -> new RiderNotFoundException(riderId.toString()));
        return toResponse(rider);
    }

    @Transactional(readOnly = true)
    public List<RiderResponse> getAllRiders() {
        return riderRepository.findAll().stream()
            .map(this::toResponse)
            .collect(Collectors.toList());
    }

    @Retryable(retryFor = ObjectOptimisticLockingFailureException.class,
               maxAttempts = 3, backoff = @Backoff(delay = 100, multiplier = 2))
    @Transactional
    public RiderResponse activateRider(UUID riderId) {
        Rider rider = riderRepository.findById(riderId)
            .orElseThrow(() -> new RiderNotFoundException(riderId.toString()));
        if (rider.getStatus() != RiderStatus.INACTIVE) {
            throw new InvalidRiderStateException(
                "Rider must be INACTIVE to activate, current: " + rider.getStatus());
        }
        rider.setStatus(RiderStatus.ACTIVE);
        rider = riderRepository.save(rider);
        outboxService.publish("Rider", rider.getId().toString(), "RiderActivated", toResponse(rider));
        logger.info("Activated rider id={}", riderId);
        return toResponse(rider);
    }

    @Retryable(retryFor = ObjectOptimisticLockingFailureException.class,
               maxAttempts = 3, backoff = @Backoff(delay = 100, multiplier = 2))
    @Transactional
    public RiderResponse suspendRider(UUID riderId) {
        Rider rider = riderRepository.findById(riderId)
            .orElseThrow(() -> new RiderNotFoundException(riderId.toString()));
        if (rider.getStatus() == RiderStatus.BLOCKED) {
            throw new InvalidRiderStateException("Cannot suspend a BLOCKED rider");
        }
        rider.setStatus(RiderStatus.SUSPENDED);
        rider = riderRepository.save(rider);

        availabilityRepository.findByRiderId(riderId).ifPresent(a -> {
            a.setAvailable(false);
            availabilityRepository.save(a);
        });

        outboxService.publish("Rider", rider.getId().toString(), "RiderSuspended", toResponse(rider));
        logger.info("Suspended rider id={}", riderId);
        return toResponse(rider);
    }

    @Retryable(retryFor = ObjectOptimisticLockingFailureException.class,
               maxAttempts = 3, backoff = @Backoff(delay = 100, multiplier = 2))
    @Transactional
    public RiderResponse onboardRider(UUID riderId) {
        Rider rider = riderRepository.findById(riderId)
            .orElseThrow(() -> new RiderNotFoundException(riderId.toString()));
        if (rider.getStatus() != RiderStatus.INACTIVE) {
            throw new InvalidRiderStateException(
                "Rider must be INACTIVE to onboard, current: " + rider.getStatus());
        }
        rider.setStatus(RiderStatus.ACTIVE);
        rider = riderRepository.save(rider);
        outboxService.publish("Rider", rider.getId().toString(), "RiderOnboarded", toResponse(rider));
        logger.info("Onboarded rider id={}", riderId);
        return toResponse(rider);
    }

    private RiderResponse toResponse(Rider rider) {
        return new RiderResponse(
            rider.getId(),
            rider.getName(),
            rider.getPhone(),
            rider.getEmail(),
            rider.getVehicleType(),
            rider.getLicenseNumber(),
            rider.getStatus(),
            rider.getRatingAvg(),
            rider.getTotalDeliveries(),
            rider.getStoreId(),
            rider.getCreatedAt(),
            rider.getUpdatedAt()
        );
    }
}
