package com.instacommerce.audit.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.audit.dto.AuditEventRequest;
import com.instacommerce.audit.service.AuditIngestionService;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import org.springframework.kafka.core.KafkaTemplate;

@ExtendWith(MockitoExtension.class)
class DomainEventConsumerTest {

    @Mock
    private AuditIngestionService ingestionService;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Mock
    private Environment env;

    private DomainEventConsumer consumer;

    @BeforeEach
    void setUp() {
        when(env.getProperty("audit.dlt-topic", "audit.DLT")).thenReturn("audit.DLT");
        consumer = new DomainEventConsumer(ingestionService, new ObjectMapper(), kafkaTemplate, env);
    }

    @Test
    void onDomainEvent_extractsUserIdFromPayloadWhenMissingOnEnvelope() {
        UUID userId = UUID.randomUUID();
        String json = """
                {
                  "eventType": "OrderCreated",
                  "aggregateType": "Order",
                  "aggregateId": "ord-123",
                  "payload": {
                    "userId": "%s",
                    "totalCents": 1500
                  }
                }
                """.formatted(userId);

        consumer.onDomainEvent(new ConsumerRecord<>("orders.events", 0, 1L, "ord-123", json));

        ArgumentCaptor<AuditEventRequest> captor = ArgumentCaptor.forClass(AuditEventRequest.class);
        verify(ingestionService).ingest(captor.capture());

        AuditEventRequest request = captor.getValue();
        assertThat(request.actorId()).isEqualTo(userId);
        assertThat(request.sourceService()).isEqualTo("order-service");
        assertThat(request.details()).containsEntry("totalCents", 1500);
    }

    @Test
    void onDomainEvent_handlesNullUserIdSafely() {
        String json = """
                {
                  "eventType": "InventoryAdjusted",
                  "payload": {
                    "userId": null,
                    "sku": "MILK-1L"
                  }
                }
                """;

        consumer.onDomainEvent(new ConsumerRecord<>("inventory.events", 0, 2L, "sku-1", json));

        ArgumentCaptor<AuditEventRequest> captor = ArgumentCaptor.forClass(AuditEventRequest.class);
        verify(ingestionService).ingest(captor.capture());

        AuditEventRequest request = captor.getValue();
        assertThat(request.actorId()).isNull();
        assertThat(request.details()).containsEntry("sku", "MILK-1L");
    }
}
