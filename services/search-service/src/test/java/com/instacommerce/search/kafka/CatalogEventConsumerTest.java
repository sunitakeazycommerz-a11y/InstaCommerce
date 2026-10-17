package com.instacommerce.search.kafka;

import com.instacommerce.search.service.SearchIndexService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class CatalogEventConsumerTest {

    @Mock
    private SearchIndexService searchIndexService;

    private CatalogEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new CatalogEventConsumer(searchIndexService);
    }

    @Test
    @DisplayName("ProductCreated with active=true upserts document")
    void shouldUpsertActiveProduct() {
        UUID productId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();

        Map<String, Object> envelope = Map.of(
            "eventType", "ProductCreated",
            "eventId", UUID.randomUUID().toString(),
            "payload", Map.of(
                "productId", productId.toString(),
                "name", "Fresh Apples",
                "description", "Organic apples",
                "brand", "FarmFresh",
                "category", "Produce",
                "priceCents", 250L,
                "imageUrl", "https://img.test/apples.jpg",
                "inStock", true,
                "active", true,
                "storeId", storeId.toString()
            )
        );

        consumer.handleCatalogEvent(envelope);

        verify(searchIndexService).upsertDocument(
            productId, "Fresh Apples", "Organic apples", "FarmFresh",
            "Produce", 250L, "https://img.test/apples.jpg", true, storeId
        );
    }

    @Test
    @DisplayName("ProductUpdated with active=false deletes document from search index")
    void shouldDeleteDeactivatedProductOnUpdate() {
        UUID productId = UUID.randomUUID();

        Map<String, Object> envelope = Map.of(
            "eventType", "ProductUpdated",
            "eventId", UUID.randomUUID().toString(),
            "payload", Map.of(
                "productId", productId.toString(),
                "name", "Discontinued Item",
                "active", false
            )
        );

        consumer.handleCatalogEvent(envelope);

        verify(searchIndexService).deleteDocument(productId);
    }

    @Test
    @DisplayName("ProductDeactivated deletes document from search index")
    void shouldDeleteOnProductDeactivated() {
        UUID productId = UUID.randomUUID();

        Map<String, Object> envelope = Map.of(
            "eventType", "ProductDeactivated",
            "eventId", UUID.randomUUID().toString(),
            "payload", Map.of(
                "productId", productId.toString()
            )
        );

        consumer.handleCatalogEvent(envelope);

        verify(searchIndexService).deleteDocument(productId);
    }

    @Test
    @DisplayName("Ignores event with null eventType")
    void shouldIgnoreNullEventType() {
        Map<String, Object> envelope = Map.of("payload", Map.of("productId", UUID.randomUUID().toString()));

        consumer.handleCatalogEvent(envelope);

        verifyNoInteractions(searchIndexService);
    }
}
