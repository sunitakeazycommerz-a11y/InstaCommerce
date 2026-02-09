package com.instacommerce.notification.provider;

import com.instacommerce.notification.domain.model.NotificationChannel;
import com.instacommerce.notification.service.MaskingUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!prod")
public class LoggingProvider implements NotificationProvider {
    private static final Logger logger = LoggerFactory.getLogger(LoggingProvider.class);

    @Override
    public boolean supports(NotificationChannel channel) {
        return true;
    }

    @Override
    public String send(NotificationSendRequest request) {
        String masked = MaskingUtil.maskRecipient(request.recipient());
        logger.info("Notification [{}] to {}: {}", request.channel(), masked, summarize(request.body()));
        return "logged";
    }

    private String summarize(String body) {
        if (body == null) {
            return "";
        }
        String trimmed = body.replaceAll("\\s+", " ").trim();
        return trimmed.length() > 120 ? trimmed.substring(0, 120) + "..." : trimmed;
    }
}
