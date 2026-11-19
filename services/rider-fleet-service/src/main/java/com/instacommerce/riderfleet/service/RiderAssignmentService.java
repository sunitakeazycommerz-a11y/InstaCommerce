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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RiderAssignmentService {
    private static final Logger logger = LoggerFactory.getLogger(RiderAssignmentService.class);
    private static final long DEFAULT_DELIVERY_FEE_CENTS = 3000L;

    private final RiderRepository riderRepository;
    private final RiderAvailabilityRepository availabilityRepository;
    private final RiderAssignmentRepository assignmentRepository;
    private final OutboxService outboxService;
    private final RiderFleetProperties properties;
    private final DispatchOptimizerClient dispatchOptimizerClient;
    private final RiderEarningsService riderEarningsService;
    private final StringRedisTemplate redisTemplate;
    private final RiderShiftService shiftService;
    private final Clock clock;

    public RiderAssignmentService(RiderRepository riderRepository,
                                   RiderAvailabilityRepository availabilityRepository,
                                   RiderAssignmentRepository assignmentRepository,
                                   OutboxService outboxService,
                                   RiderFleetProperties properties,
                                   DispatchOptimizerClient dispatchOptimizerClient) {
        this(riderRepository, availabilityRepository, assignmentRepository, outboxService,
            properties, dispatchOptimizerClient, null, (StringRedisTemplate) null, null, Clock.systemUTC());
    }

    public RiderAssignmentService(RiderRepository riderRepository,
                                   RiderAvailabilityRepository availabilityRepository,
                                   RiderAssignmentRepository assignmentRepository,
                                   OutboxService outboxService,
                                   RiderFleetProperties properties,
                                   DispatchOptimizerClient dispatchOptimizerClient,
                                   RiderEarningsService riderEarningsService) {
        this(riderRepository, availabilityRepository, assignmentRepository, outboxService,
            properties, dispatchOptimizerClient, riderEarningsService, (StringRedisTemplate) null,
            null, Clock.systemUTC());
    }

    public RiderAssignmentService(RiderRepository riderRepository,
                                   RiderAvailabilityRepository availabilityRepository,
                                   RiderAssignmentRepository assignmentRepository,
                                   OutboxService outboxService,
                                   RiderFleetProperties properties,
                                   DispatchOptimizerClient dispatchOptimizerClient,
                                   RiderEarningsService riderEarningsService,
                                   StringRedisTemplate redisTemplate) {
        this(riderRepository, availabilityRepository, assignmentRepository, outboxService,
            properties, dispatchOptimizerClient, riderEarningsService, redisTemplate, null, Clock.systemUTC());
    }

    @Autowired
    public RiderAssignmentService(RiderRepository riderRepository,
                                   RiderAvailabilityRepository availabilityRepository,
                                   RiderAssignmentRepository assignmentRepository,
                                   OutboxService outboxService,
                                   RiderFleetProperties properties,
                                   DispatchOptimizerClient dispatchOptimizerClient,
                                   RiderEarningsService riderEarningsService,
                                   ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                                   ObjectProvider<RiderShiftService> shiftServiceProvider,
                                   ObjectProvider<Clock> clockProvider) {
        this(
            riderRepository,
            availabilityRepository,
            assignmentRepository,
            outboxService,
            properties,
            dispatchOptimizerClient,
            riderEarningsService,
            redisTemplateProvider != null ? redisTemplateProvider.getIfAvailable() : null,
            shiftServiceProvider != null ? shiftServiceProvider.getIfAvailable() : null,
            clockProvider != null ? clockProvider.getIfAvailable(Clock::systemUTC) : Clock.systemUTC()
        );
    }

    public RiderAssignmentService(RiderRepository riderRepository,
                                   RiderAvailabilityRepository availabilityRepository,
                                   RiderAssignmentRepository assignmentRepository,
                                   OutboxService outboxService,
                                   RiderFleetProperties properties,
                                   DispatchOptimizerClient dispatchOptimizerClient,
                                   RiderEarningsService riderEarningsService,
                                   StringRedisTemplate redisTemplate,
                                   RiderShiftService shiftService,
                                   Clock clock) {
        this.riderRepository = riderRepository;
        this.availabilityRepository = availabilityRepository;
        this.assignmentRepository = assignmentRepository;
        this.outboxService = outboxService;
        this.properties = properties;
        this.dispatchOptimizerClient = dispatchOptimizerClient;
        this.riderEarningsService = riderEarningsService;
        this.redisTemplate = redisTemplate;
        this.shiftService = shiftService;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    /**
     * Assigns the nearest available rider to an order.
     * If the dispatch optimizer is enabled, delegates to the optimizer service first.
     * Falls back to Haversine nearest-neighbor if the optimizer is disabled, unavailable,
     * or returns no assignment. Uses pessimistic locking (FOR UPDATE SKIP LOCKED) via
     * the repository query in the Haversine path to prevent double-assignment.
     */
    @Retryable(retryFor = ObjectOptimisticLockingFailureException.class,
               maxAttempts = 3, backoff = @Backoff(delay = 100, multiplier = 2))
    @Transactional
    public UUID assignRider(UUID orderId, UUID storeId, BigDecimal pickupLat, BigDecimal pickupLng) {
        return executeAssignment(orderId, storeId, pickupLat, pickupLng, null, null, null);
    }

    @Retryable(retryFor = ObjectOptimisticLockingFailureException.class,
               maxAttempts = 3, backoff = @Backoff(delay = 100, multiplier = 2))
    @Transactional
    public UUID assignRider(UUID orderId, UUID storeId, BigDecimal pickupLat, BigDecimal pickupLng,
                            BigDecimal dropoffLat, BigDecimal dropoffLng) {
        return executeAssignment(orderId, storeId, pickupLat, pickupLng, dropoffLat, dropoffLng, null);
    }

    @Retryable(retryFor = ObjectOptimisticLockingFailureException.class,
               maxAttempts = 3, backoff = @Backoff(delay = 100, multiplier = 2))
    @Transactional
    public UUID assignRider(UUID orderId, UUID storeId, BigDecimal pickupLat, BigDecimal pickupLng,
                            BigDecimal dropoffLat, BigDecimal dropoffLng, Integer orderWeightGrams) {
        return executeAssignment(orderId, storeId, pickupLat, pickupLng, dropoffLat, dropoffLng, orderWeightGrams);
    }

    private UUID executeAssignment(UUID orderId, UUID storeId, BigDecimal pickupLat, BigDecimal pickupLng,
                                   BigDecimal dropoffLat, BigDecimal dropoffLng, Integer orderWeightGrams) {
        if (assignmentRepository.existsByOrderId(orderId)) {
            throw new DuplicateAssignmentException(orderId);
        }

        double radiusKm = properties.getAssignment().getDefaultRadiusKm();
        Instant now = Instant.now(clock);

        RiderAvailability availability = resolveRiderAvailability(
            orderId, storeId, pickupLat, pickupLng, radiusKm, orderWeightGrams, now);

        Rider rider = riderRepository.findById(availability.getRiderId())
            .orElseThrow(() -> new RiderNotFoundException(availability.getRiderId().toString()));
        if (!isRiderEligible(rider, orderWeightGrams, now)) {
            throw new NoAvailableRiderException(storeId != null ? storeId.toString() : "unknown");
        }
        if (hasFatigueCooldownExpired(rider, now)) {
            rider.setConsecutiveDeliveries(0);
        }

        rider.setStatus(RiderStatus.ON_DELIVERY);
        riderRepository.save(rider);

        availability.setAvailable(false);
        availabilityRepository.save(availability);

        RiderAssignment assignment = new RiderAssignment();
        assignment.setOrderId(orderId);
        assignment.setRiderId(rider.getId());
        assignment.setStoreId(storeId);
        assignment.setStatus("ACTIVE");
        try {
            assignmentRepository.save(assignment);
        } catch (DataIntegrityViolationException ex) {
            throw new DuplicateAssignmentException(orderId);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("riderId", rider.getId());
        payload.put("orderId", orderId);
        if (storeId != null) {
            payload.put("storeId", storeId);
        }
        if (rider.getName() != null) {
            payload.put("riderName", rider.getName());
        }
        if (pickupLat != null) {
            payload.put("pickupLat", pickupLat);
        }
        if (pickupLng != null) {
            payload.put("pickupLng", pickupLng);
        }
        if (dropoffLat != null) {
            payload.put("dropoffLat", dropoffLat);
        }
        if (dropoffLng != null) {
            payload.put("dropoffLng", dropoffLng);
        }
        if (orderWeightGrams != null) {
            payload.put("orderWeightGrams", orderWeightGrams);
        }
        payload.put("assignedAt", now.toString());
        outboxService.publish("Rider", rider.getId().toString(), "RiderAssigned", payload);

        logger.info("Assigned rider id={} to order={} store={}", rider.getId(), orderId, storeId);
        return rider.getId();
    }

    private RiderAvailability resolveRiderAvailability(UUID orderId, UUID storeId,
            BigDecimal pickupLat, BigDecimal pickupLng, double radiusKm, Integer orderWeightGrams, Instant now) {
        if (properties.getDispatch().isOptimizerEnabled()) {
            try {
                Optional<UUID> optimizedRiderId = tryOptimizerAssignment(
                    orderId, storeId, pickupLat, pickupLng, orderWeightGrams, now);
                if (optimizedRiderId.isPresent()) {
                    Optional<RiderAvailability> availability = availabilityRepository
                        .findByRiderIdForUpdate(optimizedRiderId.get());
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
        if (orderWeightGrams != null && orderWeightGrams > 0) {
            return availabilityRepository
                .findNearestAvailableWithCapacity(pickupLat, pickupLng, radiusKm, storeId, orderWeightGrams)
                .orElseThrow(() -> new NoAvailableRiderException(storeId.toString()));
        }
        return availabilityRepository
            .findNearestAvailable(pickupLat, pickupLng, radiusKm, storeId)
            .orElseThrow(() -> new NoAvailableRiderException(storeId.toString()));
    }

    private Optional<UUID> tryOptimizerAssignment(UUID orderId, UUID storeId,
            BigDecimal pickupLat, BigDecimal pickupLng, Integer orderWeightGrams, Instant now) {
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
            .filter(ra -> isRiderEligible(riderMap.get(ra.getRiderId()), orderWeightGrams, now))
            .map(ra -> buildOptimizerRiderState(ra, riderMap.get(ra.getRiderId()), storeId))
            .filter(Objects::nonNull)
            .toList();

        if (riderStates.isEmpty()) {
            return Optional.empty();
        }

        double oLat = pickupLat != null ? pickupLat.doubleValue() : 0.0;
        double oLng = pickupLng != null ? pickupLng.doubleValue() : 0.0;

        List<DispatchOptimizerClient.OrderRequest> orderRequests = List.of(
            new DispatchOptimizerClient.OrderRequest(
                orderId.toString(),
                new DispatchOptimizerClient.Position(oLat, oLng)
            )
        );

        var request = new DispatchOptimizerClient.OptimizationRequest(riderStates, orderRequests);
        return dispatchOptimizerClient.requestAssignment(request)
            .filter(result -> result.assignments() != null && !result.assignments().isEmpty())
            .flatMap(result -> {
                String assignedRiderId = result.assignments().get(0).riderId();
                if (assignedRiderId == null || assignedRiderId.isBlank()) {
                    return Optional.empty();
                }
                try {
                    return Optional.of(UUID.fromString(assignedRiderId));
                } catch (IllegalArgumentException ex) {
                    return Optional.empty();
                }
            });
    }

    private boolean isRiderEligible(Rider rider, Integer orderWeightGrams, Instant now) {
        if (rider.getStatus() != RiderStatus.ACTIVE) {
            return false;
        }
        if (!rider.canCarry(orderWeightGrams)) {
            return false;
        }
        if (isRiderFatigued(rider, now)) {
            return false;
        }
        if (properties.getAssignment().isEnforceShiftValidation() && shiftService != null
            && !shiftService.hasActiveShift(rider.getId(), now)) {
            return false;
        }
        return true;
    }

    private boolean isRiderFatigued(Rider rider, Instant now) {
        int maxConsecutive = properties.getAssignment().getMaxConsecutiveDeliveries();
        if (maxConsecutive <= 0 || rider.getConsecutiveDeliveries() < maxConsecutive) {
            return false;
        }
        return !hasFatigueCooldownExpired(rider, now);
    }

    private boolean hasFatigueCooldownExpired(Rider rider, Instant now) {
        int resetMinutes = properties.getAssignment().getFatigueResetMinutes();
        if (rider.getLastDeliveryCompletedAt() == null || resetMinutes <= 0) {
            return true;
        }
        return !now.isBefore(rider.getLastDeliveryCompletedAt().plus(Duration.ofMinutes(resetMinutes)));
    }

    private DispatchOptimizerClient.RiderState buildOptimizerRiderState(
            RiderAvailability ra, Rider r, UUID storeId) {
        double[] coords = resolveRiderCoordinates(ra);
        if (coords == null) {
            return null;
        }
        String vehicle = r.getVehicleType() != null ? r.getVehicleType().name().toLowerCase() : "scooter";
        double avgRating = r.getRatingAvg() != null ? r.getRatingAvg().doubleValue() : 5.0;
        double defaultSpeedKmh = switch (vehicle) {
            case "bicycle" -> 15.0;
            case "car" -> 28.0;
            default -> 22.0;
        };
        return new DispatchOptimizerClient.RiderState(
            ra.getRiderId().toString(),
            new DispatchOptimizerClient.Position(coords[0], coords[1]),
            0,
            vehicle,
            true,
            r.getTotalDeliveries(),
            avgRating,
            r.getConsecutiveDeliveries(),
            defaultSpeedKmh,
            100.0,
            storeId != null ? storeId.toString() : null
        );
    }

    private double[] resolveRiderCoordinates(RiderAvailability ra) {
        if (redisTemplate != null) {
            try {
                Object rawLat = redisTemplate.opsForHash().get("rider:location:" + ra.getRiderId(), "lat");
                Object rawLng = redisTemplate.opsForHash().get("rider:location:" + ra.getRiderId(), "lng");
                if (rawLat != null && rawLng != null) {
                    return new double[]{
                        Double.parseDouble(rawLat.toString()),
                        Double.parseDouble(rawLng.toString())
                    };
                }
            } catch (Exception ex) {
                logger.debug("Redis location lookup fallback to DB for rider={}: {}",
                    ra.getRiderId(), ex.getMessage());
            }
        }
        if (ra.getCurrentLat() != null && ra.getCurrentLng() != null) {
            return new double[]{ra.getCurrentLat().doubleValue(), ra.getCurrentLng().doubleValue()};
        }
        return null;
    }

    @Retryable(retryFor = ObjectOptimisticLockingFailureException.class,
               maxAttempts = 3, backoff = @Backoff(delay = 100, multiplier = 2))
    @Transactional
    public void completeDelivery(UUID orderId) {
        Optional<RiderAssignment> assignmentOpt = assignmentRepository.findByOrderId(orderId);
        if (assignmentOpt.isEmpty()) {
            logger.info("No rider assignment found to complete for order={}", orderId);
            return;
        }
        Instant now = Instant.now(clock);
        if (assignmentRepository.markCompletedIfPending(orderId, now) == 0) {
            logger.info("Rider assignment for order={} already completed; skipping duplicate completion", orderId);
            return;
        }
        RiderAssignment assignment = assignmentOpt.get();
        UUID riderId = assignment.getRiderId();

        Optional<Rider> riderOpt = riderRepository.findById(riderId);
        boolean canReleaseToAvailable = false;
        boolean fatigueReached = false;
        if (riderOpt.isPresent()) {
            Rider rider = riderOpt.get();
            RiderStatus currentStatus = rider.getStatus();
            if (currentStatus != RiderStatus.SUSPENDED
                && currentStatus != RiderStatus.INACTIVE
                && currentStatus != RiderStatus.BLOCKED) {
                int nextConsecutive = hasFatigueCooldownExpired(rider, now)
                    ? 1
                    : rider.getConsecutiveDeliveries() + 1;
                rider.setConsecutiveDeliveries(nextConsecutive);
                rider.setLastDeliveryCompletedAt(now);
                rider.setTotalDeliveries(rider.getTotalDeliveries() + 1);
                if (currentStatus == RiderStatus.ON_DELIVERY) {
                    rider.setStatus(RiderStatus.ACTIVE);
                }
                riderRepository.save(rider);
                int maxConsecutive = properties.getAssignment().getMaxConsecutiveDeliveries();
                fatigueReached = maxConsecutive > 0 && nextConsecutive >= maxConsecutive;
                canReleaseToAvailable = !fatigueReached;
            } else {
                logger.warn("Skipping availability restore on delivery completion for rider={} in status={}",
                    riderId, currentStatus);
            }
        }

        if (canReleaseToAvailable) {
            availabilityRepository.findByRiderId(riderId).ifPresent(availability -> {
                availability.setAvailable(true);
                availabilityRepository.save(availability);
            });
        } else if (fatigueReached) {
            outboxService.publish("Rider", riderId.toString(), "RiderFatigueLimitReached",
                Map.of("riderId", riderId,
                    "orderId", orderId,
                    "consecutiveDeliveries", riderOpt.get().getConsecutiveDeliveries(),
                    "cooldownMinutes", properties.getAssignment().getFatigueResetMinutes(),
                    "recordedAt", now.toString()));
            logger.warn("Rider={} reached consecutive delivery limit ({}); holding offline for fatigue rest",
                riderId, riderOpt.get().getConsecutiveDeliveries());
        }

        if (riderEarningsService != null && riderOpt.isPresent()) {
            riderEarningsService.recordEarning(orderId, riderId, DEFAULT_DELIVERY_FEE_CENTS, 0L);
        }

        outboxService.publish("Rider", riderId.toString(), "DeliveryCompleted",
            Map.of("riderId", riderId, "orderId", orderId, "completedAt", now.toString()));
        logger.info("Delivery completed for order={}, rider={} releasedBack={}", orderId, riderId, canReleaseToAvailable);
    }

    @Retryable(retryFor = ObjectOptimisticLockingFailureException.class,
               maxAttempts = 3, backoff = @Backoff(delay = 100, multiplier = 2))
    @Transactional
    public void cancelAssignment(UUID orderId, String reason) {
        Optional<RiderAssignment> assignmentOpt = assignmentRepository.findByOrderId(orderId);
        if (assignmentOpt.isEmpty()) {
            logger.info("No rider assignment found to cancel for order={}", orderId);
            return;
        }
        RiderAssignment assignment = assignmentOpt.get();
        if (!"ACTIVE".equals(assignment.getStatus())) {
            logger.info("Rider assignment for order={} already in status={}; skipping cancel",
                orderId, assignment.getStatus());
            return;
        }
        Instant now = Instant.now(clock);
        assignment.setStatus("CANCELLED");
        assignment.setCancelledAt(now);
        assignment.setCancellationReason(reason);
        assignmentRepository.save(assignment);

        UUID riderId = assignment.getRiderId();
        Optional<Rider> riderOpt = riderRepository.findById(riderId);
        if (riderOpt.isPresent()) {
            Rider rider = riderOpt.get();
            if (rider.getStatus() == RiderStatus.ON_DELIVERY) {
                rider.setStatus(RiderStatus.ACTIVE);
                riderRepository.save(rider);
            }
            if (rider.getStatus() == RiderStatus.ACTIVE) {
                availabilityRepository.findByRiderId(riderId).ifPresent(availability -> {
                    availability.setAvailable(true);
                    availabilityRepository.save(availability);
                });
            }
        }

        outboxService.publish("Rider", riderId.toString(), "RiderAssignmentCancelled",
            Map.of("riderId", riderId, "orderId", orderId, "reason", reason != null ? reason : ""));
        logger.info("Assignment cancelled for order={}, rider={} released back to ACTIVE", orderId, riderId);
    }
}
