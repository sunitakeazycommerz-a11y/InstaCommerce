package com.instacommerce.notification.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.instacommerce.notification.config.NotificationProperties;
import com.instacommerce.notification.consumer.EventEnvelope;
import com.instacommerce.notification.domain.model.NotificationChannel;
import com.instacommerce.notification.domain.model.NotificationLog;
import com.instacommerce.notification.domain.model.NotificationStatus;
import com.instacommerce.notification.dto.NotificationRequest;
import com.instacommerce.notification.infrastructure.metrics.NotificationMetrics;
import com.instacommerce.notification.infrastructure.retry.NotificationDlqPublisher;
import com.instacommerce.notification.infrastructure.retry.RetryableNotificationSender;
import com.instacommerce.notification.repository.NotificationLogRepository;
import com.instacommerce.notification.template.TemplateDefinition;
import com.instacommerce.notification.template.TemplateRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class NotificationService {
    private static final Logger logger = LoggerFactory.getLogger(NotificationService.class);

    private final TemplateRegistry templateRegistry;
    private final TemplateService templateService;
    private final DeduplicationService deduplicationService;
    private final NotificationLogRepository logRepository;
    private final RetryableNotificationSender sender;
    private final NotificationDlqPublisher dlqPublisher;
    private final UserPreferenceService userPreferenceService;
    private final UserDirectoryClient userDirectoryClient;
    private final OrderLookupClient orderLookupClient;
    private final NotificationMetrics metrics;
    private final NotificationProperties notificationProperties;

    public NotificationService(TemplateRegistry templateRegistry,
                               TemplateService templateService,
                               DeduplicationService deduplicationService,
                               NotificationLogRepository logRepository,
                               RetryableNotificationSender sender,
                               NotificationDlqPublisher dlqPublisher,
                               UserPreferenceService userPreferenceService,
                               UserDirectoryClient userDirectoryClient,
                               OrderLookupClient orderLookupClient,
                               NotificationMetrics metrics,
                               NotificationProperties notificationProperties) {
        this.templateRegistry = templateRegistry;
        this.templateService = templateService;
        this.deduplicationService = deduplicationService;
        this.logRepository = logRepository;
        this.sender = sender;
        this.dlqPublisher = dlqPublisher;
        this.userPreferenceService = userPreferenceService;
        this.userDirectoryClient = userDirectoryClient;
        this.orderLookupClient = orderLookupClient;
        this.metrics = metrics;
        this.notificationProperties = notificationProperties;
    }

    public void handleEvent(ConsumerRecord<String, String> record, EventEnvelope envelope) {
        if (envelope == null || envelope.eventType() == null) {
            return;
        }
        TemplateDefinition template = templateRegistry.resolve(envelope.eventType()).orElse(null);
        if (template == null) {
            return;
        }
        String eventId = resolveEventId(record, envelope);
        JsonNode payload = envelope.payload();
        Optional<OrderSnapshot> orderSnapshot = Optional.empty();
        UUID userId = resolveUserId(payload).orElse(null);
        if (userId == null || "PaymentRefunded".equals(envelope.eventType())) {
            orderSnapshot = resolveOrderSnapshot(payload);
            if (userId == null) {
                userId = orderSnapshot.map(OrderSnapshot::userId).orElse(null);
            }
        }
        if (userId == null) {
            logger.warn("Notification event {} missing userId", envelope.eventType());
            return;
        }
        // Cache user contact once to avoid duplicate HTTP calls (Fix N-2)
        Optional<UserContact> cachedUserContact = userDirectoryClient.findUser(userId);
        Map<String, Object> variables = buildVariables(envelope.eventType(), payload, userId,
            orderSnapshot.orElse(null), cachedUserContact.orElse(null));
        UserPreferenceService.Preferences preferences;
        try {
            preferences = userPreferenceService.getPreferences(userId);
        } catch (PreferenceLookupException ex) {
            logger.warn("Failed to resolve notification preferences for user {} on {}", userId,
                envelope.eventType(), ex);
            for (NotificationChannel channel : template.channels()) {
                persistSkipped(eventId, envelope.eventType(), userId, channel, template.templateId(),
                    "Preference lookup failed");
            }
            return;
        }
        for (NotificationChannel channel : template.channels()) {
            if (!userPreferenceService.allowNotification(preferences, channel, false)) {
                persistSkipped(eventId, envelope.eventType(), userId, channel, template.templateId(),
                    "Opted out");
                continue;
            }
            String recipient = resolveRecipient(channel, payload, cachedUserContact.orElse(null));
            if (recipient == null || recipient.isBlank()) {
                if (channel == NotificationChannel.SMS) {
                    logger.warn("SMS notification for event {} skipped — phone number is null for user {}",
                        envelope.eventType(), userId);
                    NotificationRequest dlqRequest = new NotificationRequest(
                        eventId, envelope.eventType(), userId, channel, template.templateId(), "n/a", variables);
                    dlqPublisher.publish(dlqRequest, "Phone number is null for SMS channel");
                } else if (channel == NotificationChannel.PUSH) {
                    // TODO: Implement device token management API and storage for PUSH notifications
                    logger.warn("PUSH notification skipped for event {} — no device token available. "
                        + "Device token store not yet implemented.", envelope.eventType());
                }
                persistSkipped(eventId, envelope.eventType(), userId, channel, template.templateId(),
                    "Missing recipient");
                continue;
            }
            NotificationRequest request = new NotificationRequest(
                eventId,
                envelope.eventType(),
                userId,
                channel,
                template.templateId(),
                recipient,
                variables
            );
            NotificationLog log = deduplicationService.createLog(request, MaskingUtil.maskRecipient(recipient));
            if (log == null) {
                continue;
            }
            String body = templateService.render(channel, template.templateId(), variables);
            String subject = channel == NotificationChannel.EMAIL ? template.subject() : null;
            sender.send(request, log, subject, body);
        }
    }

    private void persistSkipped(String eventId, String eventType, UUID userId, NotificationChannel channel,
                                String templateId, String reason) {
        NotificationRequest request = new NotificationRequest(eventId, eventType, userId, channel, templateId, "n/a",
            Map.of());
        NotificationLog log = deduplicationService.createLog(request, "n/a");
        if (log == null) {
            return;
        }
        log.setStatus(NotificationStatus.SKIPPED);
        log.setLastError(reason);
        logRepository.save(log);
        metrics.incrementSkipped();
    }

    private String resolveRecipient(NotificationChannel channel, JsonNode payload, UserContact userContact) {
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
                String token = textValue(payload, "deviceToken");
                if (token == null) {
                    token = textValue(payload, "pushToken");
                }
                if (token != null) {
                    return token;
                }
            }
        }
        if (userContact == null) {
            return null;
        }
        return channel == NotificationChannel.SMS ? userContact.phone() : userContact.email();
    }

    private Optional<UUID> resolveUserId(JsonNode payload) {
        if (payload == null) {
            return Optional.empty();
        }
        String userId = textValue(payload, "userId");
        if (userId == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(userId));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private Optional<OrderSnapshot> resolveOrderSnapshot(JsonNode payload) {
        if (payload == null) {
            return Optional.empty();
        }
        String orderId = textValue(payload, "orderId");
        if (orderId == null) {
            return Optional.empty();
        }
        try {
            UUID orderUuid = UUID.fromString(orderId);
            return orderLookupClient.findOrder(orderUuid);
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private Map<String, Object> buildVariables(String eventType, JsonNode payload, UUID userId,
                                               OrderSnapshot orderSnapshot, UserContact userContact) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("userName", fallbackName(payload, userContact));
        String orderId = textValue(payload, "orderId");
        if (orderId != null) {
            variables.put("orderId", orderId);
        }
        if ("OrderPlaced".equals(eventType)) {
            long total = longValue(payload, "totalCents");
            if (total == 0) {
                total = sumLineTotals(payload);
            }
            String currency = textValue(payload, "currency");
            variables.put("totalFormatted", formatCurrency(total, currency));
            variables.put("eta", resolveEta(payload));
        }
        if ("OrderDispatched".equals(eventType)) {
            variables.put("eta", resolveEta(payload));
            String riderName = textValue(payload, "riderName");
            if (riderName != null) {
                variables.put("riderName", riderName);
            }
        }
        if ("OrderDelivered".equals(eventType)) {
            String deliveredAt = textValue(payload, "deliveredAt");
            if (deliveredAt != null) {
                variables.put("deliveredAt", deliveredAt);
            }
        }
        if ("PaymentRefunded".equals(eventType)) {
            long amount = longValue(payload, "amountCents");
            if (amount == 0) {
                amount = longValue(payload, "amount");
            }
            String currency = textValue(payload, "currency");
            if (currency == null && orderSnapshot != null) {
                currency = orderSnapshot.currency();
            }
            variables.put("refundAmount", formatCurrency(amount, currency));
        }
        return variables;
    }

    private String resolveEta(JsonNode payload) {
        if (payload != null && payload.hasNonNull("estimatedMinutes")) {
            return payload.get("estimatedMinutes").asInt() + " mins";
        }
        return notificationProperties.getDelivery().getDefaultEtaMinutes() + " mins";
    }

    private String fallbackName(JsonNode payload, UserContact userContact) {
        String name = payload == null ? null : textValue(payload, "userName");
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

    private long sumLineTotals(JsonNode payload) {
        if (payload == null || payload.get("items") == null || !payload.get("items").isArray()) {
            return 0;
        }
        long total = 0;
        for (JsonNode item : payload.get("items")) {
            total += item.path("lineTotalCents").asLong(0);
        }
        return total;
    }

    private String formatCurrency(long amountCents, String currency) {
        String normalized = currency == null || currency.isBlank() ? "INR" : currency.trim().toUpperCase();
        long whole = amountCents / 100;
        long cents = Math.abs(amountCents % 100);
        return normalized + " " + whole + "." + String.format("%02d", cents);
    }

    private long longValue(JsonNode payload, String field) {
        return payload == null ? 0 : payload.path(field).asLong(0);
    }

    private String textValue(JsonNode payload, String field) {
        if (payload == null || payload.get(field) == null || payload.get(field).isNull()) {
            return null;
        }
        String value = payload.get(field).asText();
        return value.isBlank() ? null : value;
    }

    private String resolveEventId(ConsumerRecord<String, String> record, EventEnvelope envelope) {
        String eventId = record.key();
        if (eventId == null || eventId.isBlank()) {
            eventId = envelope.id();
        }
        if ((eventId == null || eventId.isBlank()) && envelope.aggregateId() != null) {
            eventId = envelope.aggregateId() + ":" + envelope.eventType();
        }
        if (eventId == null || eventId.isBlank()) {
            eventId = envelope.eventType() + ":" + record.partition() + ":" + record.offset();
        }
        return eventId.length() > 64 ? hash(eventId) : eventId;
    }

    private String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes).substring(0, 64);
        } catch (Exception ex) {
            return value.substring(0, Math.min(64, value.length()));
        }
    }
}
