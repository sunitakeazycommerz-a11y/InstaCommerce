package com.instacommerce.notification.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.instacommerce.contracts.events.EventEnvelope;
import com.instacommerce.notification.config.NotificationProperties;
import com.instacommerce.notification.domain.model.DeviceToken;
import com.instacommerce.notification.domain.model.NotificationChannel;
import com.instacommerce.notification.repository.DeviceTokenRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class NotificationPayloadSupport {
    private static final Logger logger = LoggerFactory.getLogger(NotificationPayloadSupport.class);
    private static final Set<String> DEDUPLICATED_STATUS_EVENTS = Set.of(
        "OrderCreated",
        "OrderPlaced",
        "OrderConfirmed",
        "OrderPacked",
        "OrderDispatched",
        "OrderDelivered",
        "OrderCancelled",
        "OrderFailed",
        "DeliveryFailed"
    );

    private NotificationPayloadSupport() {
    }

    static String resolveRecipient(NotificationChannel channel,
                                   JsonNode payload,
                                   UserContact userContact,
                                   UUID userId,
                                   DeviceTokenRepository deviceTokenRepository) {
        if (payload != null) {
            if (channel == NotificationChannel.EMAIL) {
                String email = textValue(payload, "email");
                if (email != null) {
                    return email;
                }
            }
            if (channel == NotificationChannel.SMS) {
                String phone = textValue(payload, "phone");
                if (phone != null) {
                    return phone;
                }
            }
            if (channel == NotificationChannel.PUSH) {
                String token = firstNonBlank(
                    textValue(payload, "deviceToken"),
                    textValue(payload, "pushToken"),
                    textValue(payload, "recipient"));
                if (token != null) {
                    return token;
                }
            }
        }
        if (channel == NotificationChannel.PUSH && userId != null) {
            return deviceTokenRepository.findFirstByUserIdAndActiveTrueOrderByUpdatedAtDesc(userId)
                .map(DeviceToken::getToken)
                .orElse(null);
        }
        if (userContact == null) {
            return null;
        }
        return switch (channel) {
            case EMAIL -> userContact.email();
            case SMS -> userContact.phone();
            case PUSH -> null;
        };
    }

    static Optional<UUID> resolveUserId(JsonNode payload) {
        String userId = textValue(payload, "userId");
        if (userId == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(userId));
        } catch (IllegalArgumentException ex) {
            logger.debug("Ignoring malformed userId '{}' in notification payload", userId, ex);
            return Optional.empty();
        }
    }

    static Optional<OrderSnapshot> resolveOrderSnapshot(JsonNode payload, OrderLookupClient orderLookupClient) {
        String orderId = textValue(payload, "orderId");
        if (orderId == null) {
            return Optional.empty();
        }
        try {
            return orderLookupClient.findOrder(UUID.fromString(orderId));
        } catch (IllegalArgumentException ex) {
            logger.debug("Ignoring malformed orderId '{}' in notification payload", orderId, ex);
            return Optional.empty();
        }
    }

    static Map<String, Object> buildVariables(String eventType,
                                              JsonNode payload,
                                              OrderSnapshot orderSnapshot,
                                              UserContact userContact,
                                              NotificationProperties properties) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("userName", fallbackName(payload, userContact));
        putIfPresent(variables, "orderId", textValue(payload, "orderId"));
        putIfPresent(variables, "phone", firstNonBlank(textValue(payload, "phone"),
            userContact != null ? userContact.phone() : null));
        putIfPresent(variables, "email", firstNonBlank(textValue(payload, "email"),
            userContact != null ? userContact.email() : null));
        putIfPresent(variables, "reason", textValue(payload, "reason"));

        switch (eventType) {
            case "ETABreachRisk" -> variables.put("eta", resolveEta(payload, properties));
            case "OrderPlaced" -> {
                long total = longValue(payload, "totalCents");
                if (total == 0) {
                    total = sumLineTotals(payload);
                }
                variables.put("totalFormatted", formatCurrency(total, textValue(payload, "currency")));
                variables.put("eta", resolveEta(payload, properties));
            }
            case "OrderDispatched" -> {
                variables.put("eta", resolveEta(payload, properties));
                putIfPresent(variables, "riderName", textValue(payload, "riderName"));
            }
            case "OrderDelivered" -> putIfPresent(variables, "deliveredAt", textValue(payload, "deliveredAt"));
            case "PaymentRefunded", "PaymentRefundFailed" -> {
                variables.putIfAbsent("orderId", "");
                variables.put("refundAmount", resolveRefundFormatted(payload, orderSnapshot));
                putIfPresent(variables, "paymentId", textValue(payload, "paymentId"));
                putIfPresent(variables, "refundId", textValue(payload, "refundId"));
                putIfPresent(variables, "failureSource", textValue(payload, "failureSource"));
            }
            case "ItemSubstituted" -> {
                variables.putIfAbsent("orderId", "");
                variables.put("originalProductId",
                    firstNonBlank(textValue(payload, "originalProductId"), "original item"));
                variables.put("substituteProductId",
                    firstNonBlank(textValue(payload, "substituteProductId"), "substitute item"));
                int qty = payload != null && payload.hasNonNull("substitutedQuantity")
                    ? payload.get("substitutedQuantity").asInt()
                    : 1;
                variables.put("substitutedQuantity", qty);
                putIfPresent(variables, "storeId", textValue(payload, "storeId"));
            }
            case "TierUpgraded" -> {
                variables.put("previousTier", firstNonBlank(textValue(payload, "previousTier"), "CURRENT"));
                variables.put("tier", firstNonBlank(textValue(payload, "tier"), "NEW"));
                variables.put("points", longValue(payload, "points"));
                variables.put("totalBalance", longValue(payload, "totalBalance"));
                variables.put("lifetimePoints", longValue(payload, "lifetimePoints"));
                putIfPresent(variables, "accountId", textValue(payload, "accountId"));
            }
            case "AccountLocked" -> {
                variables.put("lockedUntil", firstNonBlank(textValue(payload, "lockedUntil"), "shortly"));
                if (payload != null && payload.hasNonNull("failedAttempts")) {
                    variables.put("failedAttempts", payload.get("failedAttempts").asInt());
                }
            }
            default -> logger.trace("No event-specific template variables for eventType={}", eventType);
        }
        return variables;
    }

    static String resolveEventId(ConsumerRecord<String, String> record, EventEnvelope envelope) {
        if (envelope != null && envelope.eventType() != null && DEDUPLICATED_STATUS_EVENTS.contains(envelope.eventType())) {
            String orderId = textValue(envelope.payload(), "orderId");
            if (orderId == null && envelope.aggregateId() != null && !envelope.aggregateId().isBlank()) {
                orderId = envelope.aggregateId().trim();
            }
            if (orderId != null && !orderId.isBlank()) {
                String canonical = "status:" + orderId.trim() + ":" + envelope.eventType();
                return canonical.length() > 64 ? hash(canonical) : canonical;
            }
        }
        String eventId = envelope != null ? envelope.id() : null;
        if ((eventId == null || eventId.isBlank()) && envelope != null
            && envelope.aggregateId() != null && envelope.eventType() != null) {
            eventId = envelope.aggregateId() + ":" + envelope.eventType();
        }
        if (eventId == null || eventId.isBlank()) {
            eventId = record.key();
        }
        if (eventId == null || eventId.isBlank()) {
            eventId = (envelope != null ? envelope.eventType() : "UNKNOWN")
                + ":" + record.partition() + ":" + record.offset();
        }
        return eventId.length() > 64 ? hash(eventId) : eventId;
    }

    private static String resolveRefundFormatted(JsonNode payload, OrderSnapshot orderSnapshot) {
        long amount = longValue(payload, "amountCents");
        if (amount == 0) {
            amount = longValue(payload, "amount");
        }
        String currency = textValue(payload, "currency");
        if (currency == null && orderSnapshot != null) {
            currency = orderSnapshot.currency();
        }
        return formatCurrency(amount, currency);
    }

    private static String resolveEta(JsonNode payload, NotificationProperties properties) {
        if (payload != null && payload.hasNonNull("estimatedMinutes")) {
            return payload.get("estimatedMinutes").asInt() + " mins";
        }
        return properties.getDelivery().getDefaultEtaMinutes() + " mins";
    }

    private static String fallbackName(JsonNode payload, UserContact userContact) {
        String name = textValue(payload, "userName");
        if (name != null) {
            return name;
        }
        if (userContact != null && userContact.email() != null) {
            String email = userContact.email();
            int at = email.indexOf('@');
            return at > 0 ? email.substring(0, at) : "customer";
        }
        return "customer";
    }

    private static long sumLineTotals(JsonNode payload) {
        if (payload == null || payload.get("items") == null || !payload.get("items").isArray()) {
            return 0;
        }
        long total = 0;
        for (JsonNode item : payload.get("items")) {
            total += item.path("lineTotalCents").asLong(0);
        }
        return total;
    }

    private static String formatCurrency(long amountCents, String currency) {
        String normalized = currency == null || currency.isBlank() ? "INR" : currency.trim().toUpperCase(Locale.ROOT);
        long whole = amountCents / 100;
        long cents = Math.abs(amountCents % 100);
        return normalized + " " + whole + "." + String.format("%02d", cents);
    }

    private static long longValue(JsonNode payload, String field) {
        return payload == null ? 0 : payload.path(field).asLong(0);
    }

    private static String textValue(JsonNode payload, String field) {
        if (payload == null || payload.get(field) == null || payload.get(field).isNull()) {
            return null;
        }
        String value = payload.get(field).asText();
        return value.isBlank() ? null : value;
    }

    private static void putIfPresent(Map<String, Object> variables, String key, String value) {
        if (value != null) {
            variables.put(key, value);
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes).substring(0, 64);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", ex);
        }
    }
}
