package com.instacommerce.riderfleet.service;

import com.instacommerce.riderfleet.client.DispatchOptimizerClient;
import com.instacommerce.riderfleet.config.RiderFleetProperties;
import com.instacommerce.riderfleet.domain.model.Rider;
import com.instacommerce.riderfleet.domain.model.RiderAvailability;
import com.instacommerce.riderfleet.domain.model.RiderStatus;
import com.instacommerce.riderfleet.repository.RiderAvailabilityRepository;
import com.instacommerce.riderfleet.repository.RiderRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

final class RiderAssignmentSupport {
    private static final Logger logger = LoggerFactory.getLogger(RiderAssignmentSupport.class);

    private RiderAssignmentSupport() {
    }

    static boolean isRiderEligible(Rider rider,
                                   Integer orderWeightGrams,
                                   Instant now,
                                   RiderFleetProperties properties,
                                   RiderShiftService shiftService) {
        if (rider.getStatus() != RiderStatus.ACTIVE || !rider.canCarry(orderWeightGrams)) {
            return false;
        }
        if (isRiderFatigued(rider, now, properties)) {
            return false;
        }
        return !properties.getAssignment().isEnforceShiftValidation()
            || shiftService == null
            || shiftService.hasActiveShift(rider.getId(), now);
    }

    static boolean isRiderFatigued(Rider rider, Instant now, RiderFleetProperties properties) {
        int maxConsecutive = properties.getAssignment().getMaxConsecutiveDeliveries();
        if (maxConsecutive <= 0 || rider.getConsecutiveDeliveries() < maxConsecutive) {
            return false;
        }
        return !hasFatigueCooldownExpired(rider, now, properties);
    }

    static boolean hasFatigueCooldownExpired(Rider rider, Instant now, RiderFleetProperties properties) {
        int resetMinutes = properties.getAssignment().getFatigueResetMinutes();
        if (rider.getLastDeliveryCompletedAt() == null || resetMinutes <= 0) {
            return true;
        }
        return !now.isBefore(rider.getLastDeliveryCompletedAt().plus(Duration.ofMinutes(resetMinutes)));
    }

    static DispatchOptimizerClient.RiderState buildOptimizerRiderState(
            RiderAvailability availability,
            Rider rider,
            UUID storeId,
            StringRedisTemplate redisTemplate) {
        double[] coords = resolveRiderCoordinates(availability, redisTemplate);
        if (coords == null) {
            return null;
        }
        String vehicle = rider.getVehicleType() != null ? rider.getVehicleType().name().toLowerCase() : "scooter";
        double avgRating = rider.getRatingAvg() != null ? rider.getRatingAvg().doubleValue() : 5.0;
        double defaultSpeedKmh = switch (vehicle) {
            case "bicycle" -> 15.0;
            case "car" -> 28.0;
            default -> 22.0;
        };
        return new DispatchOptimizerClient.RiderState(
            availability.getRiderId().toString(),
            new DispatchOptimizerClient.Position(coords[0], coords[1]),
            0,
            vehicle,
            true,
            rider.getTotalDeliveries(),
            avgRating,
            rider.getConsecutiveDeliveries(),
            defaultSpeedKmh,
            100.0,
            storeId != null ? storeId.toString() : null
        );
    }

    private static double[] resolveRiderCoordinates(RiderAvailability availability, StringRedisTemplate redisTemplate) {
        if (redisTemplate != null) {
            try {
                Object rawLat = redisTemplate.opsForHash().get("rider:location:" + availability.getRiderId(), "lat");
                Object rawLng = redisTemplate.opsForHash().get("rider:location:" + availability.getRiderId(), "lng");
                if (rawLat != null && rawLng != null) {
                    return new double[]{
                        Double.parseDouble(rawLat.toString()),
                        Double.parseDouble(rawLng.toString())
                    };
                }
            } catch (Exception ex) {
                logger.debug("Redis location lookup fallback to DB for rider={}: {}",
                    availability.getRiderId(), ex.getMessage());
            }
        }
        if (availability.getCurrentLat() != null && availability.getCurrentLng() != null) {
            return new double[]{
                availability.getCurrentLat().doubleValue(),
                availability.getCurrentLng().doubleValue()
            };
        }
        return null;
    }

    static Optional<UUID> tryOptimizerAssignment(UUID orderId,
                                                 UUID storeId,
                                                 BigDecimal pickupLat,
                                                 BigDecimal pickupLng,
                                                 Integer orderWeightGrams,
                                                 Instant now,
                                                 RiderAvailabilityRepository availabilityRepository,
                                                 RiderRepository riderRepository,
                                                 DispatchOptimizerClient dispatchOptimizerClient,
                                                 RiderFleetProperties properties,
                                                 RiderShiftService shiftService,
                                                 StringRedisTemplate redisTemplate) {
        List<RiderAvailability> availableRiders = availabilityRepository.findByIsAvailableTrueAndStoreId(storeId);
        if (availableRiders.isEmpty()) {
            return Optional.empty();
        }
        List<UUID> riderIds = availableRiders.stream().map(RiderAvailability::getRiderId).toList();
        Map<UUID, Rider> riderMap = riderRepository.findAllById(riderIds).stream()
            .collect(Collectors.toMap(Rider::getId, Function.identity()));

        List<DispatchOptimizerClient.RiderState> riderStates = availableRiders.stream()
            .filter(ra -> riderMap.containsKey(ra.getRiderId()))
            .filter(ra -> isRiderEligible(riderMap.get(ra.getRiderId()), orderWeightGrams, now, properties, shiftService))
            .map(ra -> buildOptimizerRiderState(ra, riderMap.get(ra.getRiderId()), storeId, redisTemplate))
            .filter(Objects::nonNull)
            .toList();
        if (riderStates.isEmpty()) {
            return Optional.empty();
        }

        double oLat = pickupLat != null ? pickupLat.doubleValue() : 0.0;
        double oLng = pickupLng != null ? pickupLng.doubleValue() : 0.0;
        var request = new DispatchOptimizerClient.OptimizationRequest(
            riderStates,
            List.of(new DispatchOptimizerClient.OrderRequest(orderId.toString(),
                new DispatchOptimizerClient.Position(oLat, oLng))));
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
}
