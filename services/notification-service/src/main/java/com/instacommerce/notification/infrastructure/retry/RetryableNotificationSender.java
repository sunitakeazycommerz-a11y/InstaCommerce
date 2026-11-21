package com.instacommerce.notification.infrastructure.retry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.notification.domain.model.NotificationLog;
import com.instacommerce.notification.dto.NotificationRequest;
import com.instacommerce.notification.infrastructure.retry.NotificationProviderSender.DispatchResult;
import com.instacommerce.notification.infrastructure.retry.NotificationProviderSender.DispatchStatus;
import com.instacommerce.notification.service.ChannelFallbackService;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class RetryableNotificationSender {
    private static final Logger logger = LoggerFactory.getLogger(RetryableNotificationSender.class);
    private static final TypeReference<Map<String, Object>> VARIABLES_TYPE = new TypeReference<>() {};

    private final NotificationProviderSender providerSender;
    private final NotificationDlqPublisher dlqPublisher;
    private final ChannelFallbackService channelFallbackService;
    private final ObjectMapper objectMapper;

    public RetryableNotificationSender(NotificationProviderSender providerSender,
                                       NotificationDlqPublisher dlqPublisher,
                                       ChannelFallbackService channelFallbackService,
                                       ObjectMapper objectMapper) {
        this.providerSender = providerSender;
        this.dlqPublisher = dlqPublisher;
        this.channelFallbackService = channelFallbackService;
        this.objectMapper = objectMapper;
    }

    @Async("notificationExecutor")
    public void send(NotificationRequest request, NotificationLog log, String subject, String body) {
        log.setEventType(request.eventType());
        log.setSubject(subject);
        log.setBody(body);
        if (log.getTemplateContext() == null && request.variables() != null && !request.variables().isEmpty()) {
            try {
                log.setTemplateContext(objectMapper.writeValueAsString(request.variables()));
            } catch (JsonProcessingException ex) {
                logger.warn("Unable to serialize template context for event {}: {}", request.eventId(), ex.getMessage());
            }
        }
        attemptSend(request.channel().name(), request.recipient(), log, request);
    }

    void attemptSend(String channelName, String recipient, NotificationLog log, NotificationRequest request) {
        DispatchResult result = providerSender.dispatch(log, recipient);
        if (result.status() == DispatchStatus.FALLBACK_REQUIRED) {
            NotificationRequest req = request != null ? request : fallbackRequest(log);
            if (!channelFallbackService.tryFallback(req, log) && result.dlqReason() != null) {
                dlqPublisher.publish(req, result.dlqReason());
            }
        }
    }

    private NotificationRequest fallbackRequest(NotificationLog log) {
        return new NotificationRequest(
            log.getEventId(),
            log.getEventType() != null ? log.getEventType() : "UNKNOWN",
            log.getUserId(),
            log.getChannel(),
            log.getTemplateId(),
            log.getRecipient(),
            restoreTemplateContext(log.getTemplateContext(), log.getEventId())
        );
    }

    private Map<String, Object> restoreTemplateContext(String templateContext, String eventId) {
        if (templateContext == null || templateContext.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> restored = objectMapper.readValue(templateContext, VARIABLES_TYPE);
            return restored != null ? restored : Map.of();
        } catch (JsonProcessingException ex) {
            logger.warn("Unable to deserialize template context for event {}: {}", eventId, ex.getMessage());
            return Map.of();
        }
    }
}
