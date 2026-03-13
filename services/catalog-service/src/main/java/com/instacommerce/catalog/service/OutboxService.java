package com.instacommerce.catalog.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.catalog.domain.model.OutboxEvent;
import com.instacommerce.catalog.domain.model.Product;
import com.instacommerce.catalog.domain.model.ProductImage;
import com.instacommerce.catalog.event.ProductChangedEvent;
import com.instacommerce.catalog.repository.OutboxEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxService {
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public OutboxService(OutboxEventRepository outboxEventRepository, ObjectMapper objectMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordProductEvent(Product product, String eventType) {
        ProductChangedEvent event = new ProductChangedEvent(
            product.getId(),
            product.getSku(),
            product.getName(),
            product.getSlug(),
            product.getCategory() != null ? product.getCategory().getId() : null,
            product.isActive(),
            product.getDescription(),
            product.getBrand(),
            product.getCategory() != null ? product.getCategory().getName() : null,
            product.getBasePriceCents(),
            resolvePrimaryImageUrl(product),
            product.isActive(),
            product.getStoreId(),
            product.getUpdatedAt());
        OutboxEvent outboxEvent = new OutboxEvent();
        outboxEvent.setAggregateType("Product");
        outboxEvent.setAggregateId(product.getId().toString());
        outboxEvent.setEventType(eventType);
        outboxEvent.setPayload(writePayload(event));
        outboxEventRepository.save(outboxEvent);
    }

    private String resolvePrimaryImageUrl(Product product) {
        if (product.getImages() == null || product.getImages().isEmpty()) {
            return null;
        }
        return product.getImages().stream()
            .filter(ProductImage::isPrimary)
            .map(ProductImage::getUrl)
            .findFirst()
            .orElseGet(() -> product.getImages().get(0).getUrl());
    }

    private String writePayload(ProductChangedEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize outbox event", ex);
        }
    }
}
