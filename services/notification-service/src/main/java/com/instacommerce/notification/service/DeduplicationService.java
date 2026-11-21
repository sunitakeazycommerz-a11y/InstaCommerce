package com.instacommerce.notification.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.notification.domain.model.NotificationLog;
import com.instacommerce.notification.domain.model.NotificationStatus;
import com.instacommerce.notification.dto.NotificationRequest;
import com.instacommerce.notification.repository.NotificationLogRepository;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class DeduplicationService {
    private static final Logger logger = LoggerFactory.getLogger(DeduplicationService.class);

    private final NotificationLogRepository logRepository;
    private final ObjectMapper objectMapper;

    public DeduplicationService(NotificationLogRepository logRepository, ObjectMapper objectMapper) {
        this.logRepository = logRepository;
        this.objectMapper = objectMapper;
    }

    public NotificationLog createLog(NotificationRequest request, String maskedRecipient) {
        NotificationLog log = new NotificationLog();
        log.setUserId(request.userId());
        log.setEventId(request.eventId());
        log.setEventType(request.eventType());
        log.setChannel(request.channel());
        log.setTemplateId(request.templateId());
        String sanitizedRecipient = (maskedRecipient != null && !maskedRecipient.isBlank())
            ? maskedRecipient
            : MaskingUtil.maskRecipient(request.recipient());
        log.setRecipient(sanitizedRecipient);
        log.setStatus(NotificationStatus.PENDING);
        Map<String, Object> safeVariables = MaskingUtil.stripPiiVariables(request.variables());
        if (!safeVariables.isEmpty()) {
            try {
                log.setTemplateContext(objectMapper.writeValueAsString(safeVariables));
            } catch (JsonProcessingException ex) {
                logger.warn("Unable to serialize template context for event {} channel {}: {}",
                    request.eventId(), request.channel(), ex.getMessage());
            }
        }
        try {
            return logRepository.save(log);
        } catch (DataIntegrityViolationException ex) {
            logger.info("Duplicate notification detected for event {} channel {}", request.eventId(), request.channel());
            return null;
        }
    }
}
