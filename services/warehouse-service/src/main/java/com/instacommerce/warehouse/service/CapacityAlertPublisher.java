package com.instacommerce.warehouse.service;

import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CapacityAlertPublisher {

    private final OutboxService outboxService;

    public CapacityAlertPublisher(OutboxService outboxService) {
        this.outboxService = outboxService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publishCapacityPaused(String storeId, Map<String, Object> payload) {
        outboxService.publish("StoreCapacity", storeId, "StoreCapacityPaused", payload);
    }
}
