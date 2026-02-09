package com.instacommerce.notification.service;

import com.instacommerce.notification.domain.model.NotificationLog;
import com.instacommerce.notification.domain.model.NotificationStatus;
import com.instacommerce.notification.dto.NotificationRequest;
import com.instacommerce.notification.repository.NotificationLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class DeduplicationService {
    private static final Logger logger = LoggerFactory.getLogger(DeduplicationService.class);

    private final NotificationLogRepository logRepository;

    public DeduplicationService(NotificationLogRepository logRepository) {
        this.logRepository = logRepository;
    }

    public NotificationLog createLog(NotificationRequest request, String maskedRecipient) {
        NotificationLog log = new NotificationLog();
        log.setUserId(request.userId());
        log.setEventId(request.eventId());
        log.setChannel(request.channel());
        log.setTemplateId(request.templateId());
        log.setRecipient(request.recipient());
        log.setStatus(NotificationStatus.PENDING);
        try {
            return logRepository.save(log);
        } catch (DataIntegrityViolationException ex) {
            logger.info("Duplicate notification detected for event {} channel {}", request.eventId(), request.channel());
            return null;
        }
    }
}
