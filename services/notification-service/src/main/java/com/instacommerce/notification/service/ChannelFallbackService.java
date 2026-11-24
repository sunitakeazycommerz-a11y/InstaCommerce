package com.instacommerce.notification.service;

import com.instacommerce.notification.domain.model.NotificationChannel;
import com.instacommerce.notification.domain.model.NotificationLog;
import com.instacommerce.notification.dto.NotificationRequest;
import com.instacommerce.notification.infrastructure.retry.NotificationProviderSender;
import com.instacommerce.notification.infrastructure.retry.NotificationProviderSender.DispatchStatus;
import com.instacommerce.notification.template.TemplateDefinition;
import com.instacommerce.notification.template.TemplateRegistry;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ChannelFallbackService {
    private static final Logger logger = LoggerFactory.getLogger(ChannelFallbackService.class);

    private final DeduplicationService deduplicationService;
    private final TemplateRegistry templateRegistry;
    private final TemplateService templateService;
    private final UserPreferenceService userPreferenceService;
    private final UserDirectoryClient userDirectoryClient;
    private final NotificationProviderSender providerSender;

    public ChannelFallbackService(DeduplicationService deduplicationService,
                                  TemplateRegistry templateRegistry,
                                  TemplateService templateService,
                                  UserPreferenceService userPreferenceService,
                                  UserDirectoryClient userDirectoryClient,
                                  NotificationProviderSender providerSender) {
        this.deduplicationService = deduplicationService;
        this.templateRegistry = templateRegistry;
        this.templateService = templateService;
        this.userPreferenceService = userPreferenceService;
        this.userDirectoryClient = userDirectoryClient;
        this.providerSender = providerSender;
    }

    public boolean tryFallback(NotificationRequest failedRequest, NotificationLog failedLog) {
        String eventId = failedRequest != null ? failedRequest.eventId()
            : (failedLog != null ? failedLog.getEventId() : null);
        String eventType = failedRequest != null ? failedRequest.eventType()
            : (failedLog != null ? failedLog.getEventType() : null);
        UUID userId = failedRequest != null ? failedRequest.userId()
            : (failedLog != null ? failedLog.getUserId() : null);
        NotificationChannel currentChannel = failedRequest != null ? failedRequest.channel()
            : (failedLog != null ? failedLog.getChannel() : null);
        String templateId = failedRequest != null ? failedRequest.templateId()
            : (failedLog != null ? failedLog.getTemplateId() : null);
        Map<String, Object> variables = failedRequest != null && failedRequest.variables() != null
            ? failedRequest.variables()
            : Map.of();

        if (eventId == null || userId == null || currentChannel == null || templateId == null) {
            return false;
        }

        UserPreferenceService.Preferences preferences;
        try {
            preferences = userPreferenceService.getPreferences(userId);
        } catch (Exception ex) {
            logger.warn("Unable to load preferences during fallback for user {}: {}", userId, ex.getMessage());
            return false;
        }

        UserContact userContact = userDirectoryClient.findUser(userId).orElse(null);
        Map<String, Object> effectiveVariables = new HashMap<>(variables);
        if (!effectiveVariables.containsKey("userName")) {
            String resolvedName = (userContact != null && userContact.name() != null && !userContact.name().isBlank())
                ? userContact.name()
                : "customer";
            effectiveVariables.put("userName", resolvedName);
        }
        boolean promotional = TemplateRegistry.isPromotionalEvent(eventType);

        NotificationChannel fallbackChannel = nextChannel(currentChannel);
        while (fallbackChannel != null) {
            if (!userPreferenceService.allowNotification(preferences, fallbackChannel, promotional)) {
                fallbackChannel = nextChannel(fallbackChannel);
                continue;
            }
            String recipient = resolveFallbackRecipient(fallbackChannel, effectiveVariables, userContact);
            if (recipient == null || recipient.isBlank()) {
                fallbackChannel = nextChannel(fallbackChannel);
                continue;
            }
            String body;
            try {
                body = templateService.render(fallbackChannel, templateId, effectiveVariables);
            } catch (Exception ex) {
                logger.debug("No fallback template {} for channel {}: {}", templateId, fallbackChannel, ex.getMessage());
                fallbackChannel = nextChannel(fallbackChannel);
                continue;
            }

            String resolvedEventType = eventType != null ? eventType : "UNKNOWN";
            NotificationRequest fallbackRequest = new NotificationRequest(
                eventId,
                resolvedEventType,
                userId,
                fallbackChannel,
                templateId,
                recipient,
                effectiveVariables
            );
            NotificationLog fallbackLog = deduplicationService.createLog(
                fallbackRequest, MaskingUtil.maskRecipient(recipient, fallbackChannel));
            if (fallbackLog == null) {
                fallbackChannel = nextChannel(fallbackChannel);
                continue;
            }

            String subject = fallbackChannel == NotificationChannel.EMAIL
                ? resolveSubject(eventType, failedLog)
                : null;
            fallbackLog.setEventType(resolvedEventType);
            fallbackLog.setSubject(subject);
            fallbackLog.setBody(body);
            logger.info("Triggering channel fallback {} -> {} for event {}", currentChannel, fallbackChannel, eventId);
            DispatchStatus dispatchStatus = providerSender.dispatch(fallbackLog, recipient).status();
            if (dispatchStatus != DispatchStatus.FALLBACK_REQUIRED) {
                return true;
            }
            fallbackChannel = nextChannel(fallbackChannel);
        }
        return false;
    }

    public static NotificationChannel nextChannel(NotificationChannel current) {
        if (current == null) {
            return null;
        }
        return switch (current) {
            case PUSH -> NotificationChannel.SMS;
            case SMS -> NotificationChannel.EMAIL;
            case EMAIL -> null;
        };
    }

    private String resolveFallbackRecipient(NotificationChannel channel,
                                            Map<String, Object> variables,
                                            UserContact userContact) {
        if (channel == NotificationChannel.SMS) {
            Object phoneVar = variables.get("phone");
            if (phoneVar instanceof String s && !s.isBlank()) {
                return s.trim();
            }
            return userContact != null ? userContact.phone() : null;
        }
        if (channel == NotificationChannel.EMAIL) {
            Object emailVar = variables.get("email");
            if (emailVar instanceof String s && !s.isBlank()) {
                return s.trim();
            }
            return userContact != null ? userContact.email() : null;
        }
        return null;
    }

    private String resolveSubject(String eventType, NotificationLog failedLog) {
        if (failedLog != null && failedLog.getSubject() != null && !failedLog.getSubject().isBlank()) {
            return failedLog.getSubject();
        }
        if (eventType != null && templateRegistry != null) {
            Optional<TemplateDefinition> def = templateRegistry.resolve(eventType);
            if (def.isPresent() && def.get().subject() != null) {
                return def.get().subject();
            }
        }
        return "InstaCommerce Notification";
    }
}
