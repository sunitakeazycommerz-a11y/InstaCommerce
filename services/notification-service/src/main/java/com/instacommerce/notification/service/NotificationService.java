package com.instacommerce.notification.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.instacommerce.contracts.events.EventEnvelope;
import com.instacommerce.notification.config.NotificationProperties;
import com.instacommerce.notification.domain.model.NotificationChannel;
import com.instacommerce.notification.domain.model.NotificationLog;
import com.instacommerce.notification.domain.model.NotificationStatus;
import com.instacommerce.notification.dto.NotificationRequest;
import com.instacommerce.notification.infrastructure.metrics.NotificationMetrics;
import com.instacommerce.notification.infrastructure.retry.NotificationDlqPublisher;
import com.instacommerce.notification.infrastructure.retry.RetryableNotificationSender;
import com.instacommerce.notification.repository.DeviceTokenRepository;
import com.instacommerce.notification.repository.NotificationLogRepository;
import com.instacommerce.notification.template.TemplateDefinition;
import com.instacommerce.notification.template.TemplateRegistry;
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
    private final DeviceTokenRepository deviceTokenRepository;
    private final ChannelFallbackService channelFallbackService;

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
                               NotificationProperties notificationProperties,
                               DeviceTokenRepository deviceTokenRepository,
                               ChannelFallbackService channelFallbackService) {
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
        this.deviceTokenRepository = deviceTokenRepository;
        this.channelFallbackService = channelFallbackService;
    }

    public void handleEvent(ConsumerRecord<String, String> record, EventEnvelope envelope) {
        if (envelope == null || envelope.eventType() == null) {
            return;
        }
        TemplateDefinition template = templateRegistry.resolve(envelope.eventType()).orElse(null);
        if (template == null) {
            return;
        }
        String eventId = NotificationPayloadSupport.resolveEventId(record, envelope);
        JsonNode payload = envelope.payload();
        Optional<OrderSnapshot> orderSnapshot = Optional.empty();
        UUID userId = NotificationPayloadSupport.resolveUserId(payload).orElse(null);
        if (userId == null
            || "PaymentRefunded".equals(envelope.eventType())
            || "PaymentRefundFailed".equals(envelope.eventType())) {
            orderSnapshot = NotificationPayloadSupport.resolveOrderSnapshot(payload, orderLookupClient);
            if (userId == null) {
                userId = orderSnapshot.map(OrderSnapshot::userId).orElse(null);
            }
        }
        if (userId == null) {
            logger.warn("Notification event {} missing userId", envelope.eventType());
            return;
        }
        Optional<UserContact> cachedUserContact = userDirectoryClient.findUser(userId);
        Map<String, Object> variables = NotificationPayloadSupport.buildVariables(
            envelope.eventType(), payload, orderSnapshot.orElse(null),
            cachedUserContact.orElse(null), notificationProperties);
        UserPreferenceService.Preferences preferences = userPreferenceService.getPreferences(userId);
        boolean promotional = TemplateRegistry.isPromotionalEvent(envelope.eventType());
        for (NotificationChannel channel : template.channels()) {
            dispatchToChannel(record, envelope, template, eventId, payload, userId,
                cachedUserContact, variables, preferences, promotional, channel);
        }
    }

    private void dispatchToChannel(ConsumerRecord<String, String> record,
                                   EventEnvelope envelope,
                                   TemplateDefinition template,
                                   String eventId,
                                   JsonNode payload,
                                   UUID userId,
                                   Optional<UserContact> cachedUserContact,
                                   Map<String, Object> variables,
                                   UserPreferenceService.Preferences preferences,
                                   boolean promotional,
                                   NotificationChannel channel) {
        if (!userPreferenceService.allowNotification(preferences, channel, promotional)) {
            persistSkipped(eventId, envelope.eventType(), userId, channel, template.templateId(),
                promotional && userPreferenceService.isWithinQuietHours(preferences)
                    ? "Suppressed during quiet hours"
                    : "Opted out");
            return;
        }
        String recipient = NotificationPayloadSupport.resolveRecipient(
            channel, payload, cachedUserContact.orElse(null), userId, deviceTokenRepository);
        if (recipient == null || recipient.isBlank()) {
            handleMissingRecipient(envelope.eventType(), eventId, userId, channel, template,
                cachedUserContact, variables);
            return;
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
            return;
        }
        String body = templateService.render(channel, template.templateId(), variables);
        String subject = channel == NotificationChannel.EMAIL ? template.subject() : null;
        sender.send(request, log, subject, body);
    }

    private void handleMissingRecipient(String eventType,
                                        String eventId,
                                        UUID userId,
                                        NotificationChannel channel,
                                        TemplateDefinition template,
                                        Optional<UserContact> cachedUserContact,
                                        Map<String, Object> variables) {
        if (channel == NotificationChannel.SMS) {
            logger.warn("SMS notification for event {} skipped — phone number is null for user {}",
                eventType, userId);
            NotificationRequest dlqRequest = new NotificationRequest(
                eventId, eventType, userId, channel, template.templateId(), "n/a", variables);
            dlqPublisher.publish(dlqRequest, "Phone number is null for SMS channel");
        } else if (channel == NotificationChannel.PUSH) {
            logger.warn("PUSH notification skipped for event {} — no active device token for user {}",
                eventType, userId);
        }
        persistSkipped(eventId, eventType, userId, channel, template.templateId(), "Missing recipient");
        if (channel == NotificationChannel.PUSH
            && channelFallbackService != null
            && (variables.containsKey("phone") || variables.containsKey("email") || cachedUserContact.isPresent())
            && !template.channels().contains(NotificationChannel.SMS)
            && !template.channels().contains(NotificationChannel.EMAIL)) {
            NotificationRequest failedPush = new NotificationRequest(
                eventId, eventType, userId, channel, template.templateId(), "n/a", variables);
            channelFallbackService.tryFallback(failedPush, null);
        }
    }

    private void persistSkipped(String eventId, String eventType, UUID userId, NotificationChannel channel,
                                String templateId, String reason) {
        NotificationRequest request = new NotificationRequest(
            eventId, eventType, userId, channel, templateId, "n/a", Map.of());
        NotificationLog log = deduplicationService.createLog(request, "n/a");
        if (log == null) {
            return;
        }
        log.setStatus(NotificationStatus.SKIPPED);
        log.setLastError(reason);
        logRepository.save(log);
        metrics.incrementSkipped();
    }
}
