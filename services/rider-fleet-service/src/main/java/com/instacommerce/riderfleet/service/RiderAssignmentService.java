package com.instacommerce.riderfleet.service;

import com.instacommerce.riderfleet.client.DispatchOptimizerClient;
import com.instacommerce.riderfleet.config.RiderFleetProperties;
import com.instacommerce.riderfleet.domain.model.Rider;
import com.instacommerce.riderfleet.domain.model.RiderAssignment;
import com.instacommerce.riderfleet.domain.model.RiderAvailability;
import com.instacommerce.riderfleet.domain.model.RiderStatus;
import com.instacommerce.riderfleet.exception.DuplicateAssignmentException;
import com.instacommerce.riderfleet.exception.NoAvailableRiderException;
import com.instacommerce.riderfleet.exception.RiderNotFoundException;
import com.instacommerce.riderfleet.repository.RiderAssignmentRepository;
import com.instacommerce.riderfleet.repository.RiderAvailabilityRepository;
import com.instacommerce.riderfleet.repository.RiderRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RiderAssignmentService {
    private static final Logger logger = LoggerFactory.getLogger(RiderAssignmentService.class);

    private final RiderRepository riderRepository;
    private final RiderAvailabilityRepository availabilityRepository;
    private final RiderAssignmentRepository assignmentRepository;
    private final OutboxService outboxService;
    private final RiderFleetProperties properties;
    private final DispatchOptimizerClient dispatchOptimizerClient;

    public RiderAssignmentService(RiderRepository riderRepository,
                                   RiderAvailabilityRepository availabilityRepository,
                                   RiderAssignmentRepository assignmentRepository,
                                   OutboxService outboxService,
                                   RiderFleetProperties properties,
                                   DispatchOptimizerClient dispatchOptimizerClient) {
        this.riderRepository = riderRepository;
        this.availabilityRepository = availabilityRepository;
        this.assignmentRepository = assignmentRepository;
        this.outboxService = outboxService;
        this.properties = properties;
        this.dispatchOptimizerClient = dispatchOptimizerClient;
    }

    /**
     * Assigns the nearest available rider to an order.
     * If the dispatch optimizer is enabled, delegates to the optimizer service first.
     * Falls back to Haversine nearest-neighbor if the optimizer is disabled, unavailable,
     * or returns no assignment. Uses pessimistic locking (FOR UPDATE SKIP LOCKED) via
     * the repository query in the Haversine path to prevent double-assignment.
     */
    @Transactional
    public UUID assignRider(UUID orderId, UUID storeId, BigDecimal pickupLat, BigDecimal pickupLng) {
        if (assignmentRepository.existsByOrderId(orderId)) {
            throw new DuplicateAssignmentException(orderId);
        }

        double radiusKm = properties.getAssignment().getDefaultRadiusKm();

        RiderAvailability availability = resolveRiderAvailability(
            orderId, storeId, pickupLat, pickupLng, radiusKm);

        Rider rider = riderRepository.findById(availability.getRiderId())
            .orElseThrow(() -> new RiderNotFoundException(availability.getRiderId().toString()));

        rider.setStatus(RiderStatus.ON_DELIVERY);
        riderRepository.save(rider);

        availability.setAvailable(false);
        availabilityRepository.save(availability);

        RiderAssignment assignment = new RiderAssignment();
        assignment.setOrderId(orderId);
        assignment.setRiderId(rider.getId());
        assignment.setStoreId(storeId);
        try {
            assignmentRepository.save(assignment);
        } catch (DataIntegrityViolationException ex) {
            throw new DuplicateAssignmentException(orderId);
        }

        outboxService.publish("Rider", rider.getId().toString(), "RiderAssigned",
            Map.of("riderId", rider.getId(), "orderId", orderId, "storeId", storeId));

        logger.info("Assigned rider id={} to order={} store={}", rider.getId(), orderId, storeId);
        return rider.getId();
    }

    private RiderAvailability resolveRiderAvailability(UUID orderId, UUID storeId,
            BigDecimal pickupLat, BigDecimal pickupLng, double radiusKm) {
        if (properties.getDispatch().isOptimizerEnabled()) {
            try {
                Optional<UUID> optimizedRiderId = tryOptimizerAssignment(
                    orderId, storeId, pickupLat, pickupLng);
                if (optimizedRiderId.isPresent()) {
                    Optional<RiderAvailability> availability = availabilityRepository
                        .findByRiderId(optimizedRiderId.get());
                    if (availability.isPresent() && availability.get().isAvailable()) {
                        logger.info("Using dispatch optimizer for order={}, assigned rider={}",
                            orderId, optimizedRiderId.get());
                        return availability.get();
                    }
                    logger.warn("Optimizer-selected rider={} no longer available for order={}, "
                        + "falling back to Haversine", optimizedRiderId.get(), orderId);
                }
            } catch (Exception ex) {
                logger.warn("Dispatch optimizer call failed for order={}, falling back to Haversine",
                    orderId, ex);
            }
        }

        logger.info("Using Haversine nearest-neighbor for rider assignment, order={}", orderId);
        return availabilityRepository
            .findNearestAvailable(pickupLat, pickupLng, radiusKm, storeId)
            .orElseThrow(() -> new NoAvailableRiderException(storeId.toString()));
    }

    private Optional<UUID> tryOptimizerAssignment(UUID orderId, UUID storeId,
            BigDecimal pickupLat, BigDecimal pickupLng) {
        List<RiderAvailability> availableRiders = availabilityRepository
            .findByIsAvailableTrueAndStoreId(storeId);
        if (availableRiders.isEmpty()) {
            return Optional.empty();
        }

        List<UUID> riderIds = availableRiders.stream()
            .map(RiderAvailability::getRiderId)
            .toList();
        Map<UUID, Rider> riderMap = riderRepository.findAllById(riderIds).stream()
            .collect(Collectors.toMap(Rider::getId, Function.identity()));

        List<DispatchOptimizerClient.RiderState> riderStates = availableRiders.stream()
            .filter(ra -> riderMap.containsKey(ra.getRiderId()))
            .filter(ra -> riderMap.get(ra.getRiderId()).getStatus() == RiderStatus.ACTIVE)
            .map(ra -> {
                Rider r = riderMap.get(ra.getRiderId());
                return new DispatchOptimizerClient.RiderState(
                    ra.getRiderId(),
                    ra.getCurrentLat(),
                    ra.getCurrentLng(),
                    r.getRatingAvg(),
                    0,
                    r.getVehicleType().name(),
                    true
                );
            })
            .toList();

        if (riderStates.isEmpty()) {
            return Optional.empty();
        }

        List<DispatchOptimizerClient.OrderRequest> orderRequests = List.of(
            new DispatchOptimizerClient.OrderRequest(
                orderId, storeId, pickupLat, pickupLng, null, null
            )
        );

        var request = new DispatchOptimizerClient.OptimizationRequest(riderStates, orderRequests);
        return dispatchOptimizerClient.requestAssignment(request)
            .filter(result -> result.assignments() != null && !result.assignments().isEmpty())
            .map(result -> result.assignments().get(0).riderId());
    }
}
