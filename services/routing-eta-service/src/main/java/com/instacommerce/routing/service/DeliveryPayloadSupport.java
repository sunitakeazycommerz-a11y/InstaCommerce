package com.instacommerce.routing.service;

import com.instacommerce.platform.geo.GeoUtils;
import com.instacommerce.routing.config.RoutingProperties;
import com.instacommerce.routing.domain.model.Delivery;
import com.instacommerce.routing.domain.model.DeliveryStatus;
import com.instacommerce.routing.dto.response.DeliveryResponse;
import com.instacommerce.routing.dto.response.ETAEstimate;
import com.instacommerce.routing.exception.InvalidDeliveryStateException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

final class DeliveryPayloadSupport {
    private DeliveryPayloadSupport() {
    }

    static Map<String, Object> statusPayload(Delivery saved) {
        Map<String, Object> statusPayload = new HashMap<>();
        statusPayload.put("deliveryId", saved.getId().toString());
        statusPayload.put("orderId", saved.getOrderId().toString());
        statusPayload.put("riderId", saved.getRiderId() != null ? saved.getRiderId().toString() : "");
        statusPayload.put("status", saved.getStatus().name());
        statusPayload.put("updatedAt", saved.getUpdatedAt() != null
            ? saved.getUpdatedAt().toString() : Instant.now().toString());
        return statusPayload;
    }

    static Map<String, Object> etaUpdatePayload(Delivery delivery, ETAEstimate estimate) {
        Map<String, Object> updatePayload = new HashMap<>();
        updatePayload.put("deliveryId", delivery.getId().toString());
        updatePayload.put("orderId", delivery.getOrderId().toString());
        updatePayload.put("riderId", delivery.getRiderId() != null ? delivery.getRiderId().toString() : "");
        updatePayload.put("estimatedMinutes", estimate.etaMinutes());
        updatePayload.put("etaLowMinutes", estimate.etaLowMinutes());
        updatePayload.put("etaHighMinutes", estimate.etaHighMinutes());
        updatePayload.put("distanceKm", estimate.distanceKm());
        updatePayload.put("detourDetected", estimate.detourDetected());
        updatePayload.put("monsoonDelayActive", estimate.monsoonDelayActive());
        updatePayload.put("delayBanner", estimate.delayBanner());
        updatePayload.put("vehicleRecommendation", estimate.vehicleRecommendation());
        updatePayload.put("batchStops", estimate.batchStops());
        updatePayload.put("dwellMinutes", estimate.dwellMinutes());
        updatePayload.put("breachProbability", estimate.breachProbability());
        updatePayload.put("calculatedAt", estimate.calculatedAt().toString());
        return updatePayload;
    }

    static Map<String, Object> breachRiskPayload(Delivery delivery,
                                                 ETAEstimate estimate,
                                                 RoutingProperties routingProperties,
                                                 int creditCents,
                                                 String currency) {
        Map<String, Object> breachPayload = new HashMap<>();
        breachPayload.put("deliveryId", delivery.getId().toString());
        breachPayload.put("orderId", delivery.getOrderId().toString());
        breachPayload.put("riderId", delivery.getRiderId() != null ? delivery.getRiderId().toString() : "");
        breachPayload.put("currentEtaMinutes", estimate.etaMinutes());
        breachPayload.put("etaLowMinutes", estimate.etaLowMinutes());
        breachPayload.put("etaHighMinutes", estimate.etaHighMinutes());
        breachPayload.put("slaMinutes", routingProperties.getBreach().getSlaThresholdMinutes());
        breachPayload.put("breachProbability", estimate.breachProbability());
        breachPayload.put("timestamp", Instant.now().toString());
        breachPayload.put("autoCompensationCents", creditCents);
        breachPayload.put("currency", currency);
        breachPayload.put("customerMessage", formatApologyMessage(creditCents, currency));
        breachPayload.put("priorityEscalation", true);
        return breachPayload;
    }

    static String resolveApologyCurrency(RoutingProperties routingProperties) {
        String configured = routingProperties.getBreach().getApologyCreditCurrency();
        return (configured == null || configured.isBlank()) ? "INR" : configured.trim().toUpperCase(Locale.ROOT);
    }

    static String formatApologyMessage(int creditCents, String currency) {
        String formattedCredit;
        if ("INR".equals(currency)) {
            formattedCredit = creditCents % 100 == 0
                ? "₹" + (creditCents / 100)
                : String.format(Locale.ROOT, "₹%.2f", creditCents / 100.0);
        } else {
            formattedCredit = String.format(Locale.ROOT, "%s %.2f", currency, creditCents / 100.0);
        }
        return "We're running slightly late! Here is a " + formattedCredit + " apology wallet credit on us.";
    }

    static DeliveryResponse toResponse(Delivery d) {
        return new DeliveryResponse(
            d.getId(), d.getOrderId(), d.getRiderId(), d.getStoreId(),
            d.getPickupLat(), d.getPickupLng(), d.getDropoffLat(), d.getDropoffLng(),
            d.getStatus(), d.getEstimatedMinutes(), d.getActualMinutes(),
            d.getDistanceKm(), d.getStartedAt(), d.getDeliveredAt(),
            d.getCreatedAt(), d.getUpdatedAt(),
            d.isBreachRisk(), d.isCompensationTriggered(), d.getCompensationAmountCents(),
            d.getVehicleType(), d.isWeatherDelayActive());
    }

    static int countPrecedingStops(List<Delivery> active,
                                   UUID targetDeliveryId,
                                   Instant targetCreatedAt,
                                   BigDecimal targetDropoffLat,
                                   BigDecimal targetDropoffLng,
                                   double fromLat,
                                   double fromLng) {
        if (active == null || active.size() <= 1) {
            return 0;
        }
        double targetDistanceKm = (targetDropoffLat != null && targetDropoffLng != null)
            ? GeoUtils.haversineKm(fromLat, fromLng, targetDropoffLat.doubleValue(), targetDropoffLng.doubleValue())
            : Double.MAX_VALUE;
        int preceding = 0;
        for (Delivery other : active) {
            if (other.getId().equals(targetDeliveryId)) {
                continue;
            }
            if (other.getDropoffLat() != null && other.getDropoffLng() != null) {
                double otherDistanceKm = GeoUtils.haversineKm(
                    fromLat, fromLng, other.getDropoffLat().doubleValue(), other.getDropoffLng().doubleValue());
                if (otherDistanceKm < targetDistanceKm - 1e-6) {
                    preceding++;
                    continue;
                }
                if (Math.abs(otherDistanceKm - targetDistanceKm) <= 1e-6
                    && other.getCreatedAt() != null && targetCreatedAt != null
                    && other.getCreatedAt().isBefore(targetCreatedAt)) {
                    preceding++;
                }
            }
        }
        return preceding;
    }

    static void validateTransition(DeliveryStatus current, DeliveryStatus target) {
        boolean valid = switch (current) {
            case PENDING -> target == DeliveryStatus.RIDER_ASSIGNED
                || target == DeliveryStatus.ASSIGNED
                || target == DeliveryStatus.FAILED;
            case RIDER_ASSIGNED, ASSIGNED -> target == DeliveryStatus.PICKED_UP
                || target == DeliveryStatus.FAILED;
            case PICKED_UP -> target == DeliveryStatus.EN_ROUTE
                || target == DeliveryStatus.IN_TRANSIT
                || target == DeliveryStatus.FAILED;
            case EN_ROUTE, IN_TRANSIT -> target == DeliveryStatus.NEAR_DESTINATION
                || target == DeliveryStatus.DELIVERED
                || target == DeliveryStatus.FAILED;
            case NEAR_DESTINATION -> target == DeliveryStatus.DELIVERED
                || target == DeliveryStatus.FAILED;
            case DELIVERED, FAILED -> false;
        };
        if (!valid) {
            throw new InvalidDeliveryStateException(current, target);
        }
    }

    static Delivery buildNewDelivery(UUID orderId,
                                     UUID riderId,
                                     UUID storeId,
                                     BigDecimal pickupLat,
                                     BigDecimal pickupLng,
                                     BigDecimal dropoffLat,
                                     BigDecimal dropoffLng,
                                     String effectiveVehicle,
                                     ETAEstimate eta) {
        Delivery delivery = new Delivery();
        delivery.setOrderId(orderId);
        delivery.setRiderId(riderId);
        delivery.setStoreId(storeId);
        delivery.setPickupLat(pickupLat);
        delivery.setPickupLng(pickupLng);
        delivery.setDropoffLat(dropoffLat);
        delivery.setDropoffLng(dropoffLng);
        delivery.setVehicleType(effectiveVehicle);
        delivery.setEstimatedMinutes(eta.etaMinutes());
        delivery.setEtaLowMinutes(eta.etaLowMinutes());
        delivery.setEtaHighMinutes(eta.etaHighMinutes());
        delivery.setLastEtaUpdatedAt(eta.calculatedAt());
        delivery.setDistanceKm(eta.distanceKm());
        if (eta.monsoonDelayActive()) {
            delivery.setWeatherDelayActive(true);
        }
        if (riderId != null) {
            delivery.setStatus(DeliveryStatus.RIDER_ASSIGNED);
            delivery.setStartedAt(Instant.now());
        }
        return delivery;
    }

    static List<Delivery> selectGeofenceTargets(List<Delivery> candidates, UUID zoneId) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        if (zoneId != null) {
            List<Delivery> matchedByZone = candidates.stream()
                .filter(d -> zoneId.equals(d.getStoreId()) || zoneId.equals(d.getId()) || zoneId.equals(d.getOrderId()))
                .toList();
            if (!matchedByZone.isEmpty()) {
                return matchedByZone;
            }
        }
        return candidates;
    }
}
