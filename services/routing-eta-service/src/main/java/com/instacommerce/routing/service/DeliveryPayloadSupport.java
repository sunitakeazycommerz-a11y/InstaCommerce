package com.instacommerce.routing.service;

import com.instacommerce.routing.config.RoutingProperties;
import com.instacommerce.routing.domain.model.Delivery;
import com.instacommerce.routing.dto.response.DeliveryResponse;
import com.instacommerce.routing.dto.response.ETAEstimate;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

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
}
