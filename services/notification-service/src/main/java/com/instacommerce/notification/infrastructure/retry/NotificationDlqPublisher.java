package com.instacommerce.notification.infrastructure.retry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.notification.config.NotificationProperties;
import com.instacommerce.notification.dto.NotificationRequest;
import com.instacommerce.notification.service.MaskingUtil;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class NotificationDlqPublisher {
    private static final Logger logger = LoggerFactory.getLogger(NotificationDlqPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final NotificationProperties notificationProperties;
    private final ObjectMapper objectMapper;

    public NotificationDlqPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                    NotificationProperties notificationProperties,
                                    ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.notificationProperties = notificationProperties;
        this.objectMapper = objectMapper;
    }

    public void publish(NotificationRequest request, String reason) {
        Map<String, Object> payload = Map.of(
            "eventId", request.eventId(),
            "eventType", request.eventType(),
            "channel", request.channel().name(),
            "reason", reason,
            "maskedRecipient", MaskingUtil.maskRecipient(request.recipient())
        );
        try {
            String body = objectMapper.writeValueAsString(payload);
            kafkaTemplate.send(notificationProperties.getDlqTopic(), request.eventId(), body);
        } catch (JsonProcessingException ex) {
            logger.warn("Failed to serialize notification DLQ payload", ex);
        }
    }
}
