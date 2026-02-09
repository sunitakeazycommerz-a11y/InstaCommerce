package com.instacommerce.catalog.service;

import com.instacommerce.catalog.domain.model.OutboxEvent;

public interface OutboxEventPublisher {
    void publish(OutboxEvent event);
}
