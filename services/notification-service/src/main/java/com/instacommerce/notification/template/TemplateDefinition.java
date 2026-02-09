package com.instacommerce.notification.template;

import com.instacommerce.notification.domain.model.NotificationChannel;
import java.util.List;

public record TemplateDefinition(
    String templateId,
    List<NotificationChannel> channels,
    String subject
) {
}
