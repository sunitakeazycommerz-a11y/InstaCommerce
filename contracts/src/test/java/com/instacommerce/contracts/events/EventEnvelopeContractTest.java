package com.instacommerce.contracts.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.instacommerce.contracts.topics.TopicNames;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EventEnvelopeContractTest {

    private static final ObjectMapper MAPPER = buildMapper();

    private static ObjectMapper buildMapper() {
        SimpleModule instantModule = new SimpleModule();
        instantModule.addSerializer(
                Instant.class,
                new JsonSerializer<>() {
                    @Override
                    public void serialize(Instant value, JsonGenerator gen, SerializerProvider serializers)
                            throws IOException {
                        gen.writeString(value.toString());
                    }
                });
        instantModule.addDeserializer(
                Instant.class,
                new JsonDeserializer<>() {
                    @Override
                    public Instant deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
                        return Instant.parse(p.getValueAsString());
                    }
                });
        return new ObjectMapper().registerModule(instantModule);
    }

    @Test
    @DisplayName("EventEnvelope deserializes both camelCase and snake_case / legacy alias JSON")
    void shouldDeserializeCamelAndSnakeCaseEnvelopes() throws Exception {
        String camelJson = """
                {
                  "id": "11111111-1111-1111-1111-111111111111",
                  "eventType": "OrderPlaced",
                  "aggregateType": "Order",
                  "aggregateId": "22222222-2222-2222-2222-222222222222",
                  "eventTime": "2026-11-19T10:15:30Z",
                  "schemaVersion": "1.0.0",
                  "sourceService": "order-service",
                  "correlationId": "corr-camel-1",
                  "payload": { "orderId": "22222222-2222-2222-2222-222222222222", "totalCents": 1499 }
                }
                """;

        String snakeJson = """
                {
                  "event_id": "33333333-3333-3333-3333-333333333333",
                  "event_type": "RiderGeofenceTransition",
                  "aggregate_type": "Rider",
                  "aggregate_id": "rider-42",
                  "occurred_at": "2026-11-19T10:20:00Z",
                  "schema_version": "v1",
                  "source_service": "location-ingestion-service",
                  "correlation_id": "corr-snake-2",
                  "payload": { "rider_id": "rider-42", "zone_id": "zone-sf-1" }
                }
                """;

        EventEnvelope camel = MAPPER.readValue(camelJson, EventEnvelope.class);
        EventEnvelope snake = MAPPER.readValue(snakeJson, EventEnvelope.class);

        assertThat(camel.id()).isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(camel.eventType()).isEqualTo("OrderPlaced");
        assertThat(camel.aggregateType()).isEqualTo("Order");
        assertThat(camel.aggregateId()).isEqualTo("22222222-2222-2222-2222-222222222222");
        assertThat(camel.eventTime()).isEqualTo(Instant.parse("2026-11-19T10:15:30Z"));
        assertThat(camel.schemaVersion()).isEqualTo("1.0.0");
        assertThat(camel.sourceService()).isEqualTo("order-service");
        assertThat(camel.correlationId()).isEqualTo("corr-camel-1");
        assertThat(camel.payload().get("totalCents").asInt()).isEqualTo(1499);

        assertThat(snake.id()).isEqualTo("33333333-3333-3333-3333-333333333333");
        assertThat(snake.eventType()).isEqualTo("RiderGeofenceTransition");
        assertThat(snake.aggregateType()).isEqualTo("Rider");
        assertThat(snake.aggregateId()).isEqualTo("rider-42");
        assertThat(snake.eventTime()).isEqualTo(Instant.parse("2026-11-19T10:20:00Z"));
        assertThat(snake.schemaVersion()).isEqualTo("v1");
        assertThat(snake.sourceService()).isEqualTo("location-ingestion-service");
        assertThat(snake.correlationId()).isEqualTo("corr-snake-2");
        assertThat(snake.payload().get("zone_id").asText()).isEqualTo("zone-sf-1");
    }

    @Test
    @DisplayName("EventEnvelope record components and JsonAlias names match EventEnvelope.v1.json")
    void recordAliasesShouldMatchEnvelopeSchemaProperties() throws Exception {
        Path envelopeSchema = Path.of("src/main/resources/schemas/common/EventEnvelope.v1.json");
        if (!Files.exists(envelopeSchema)) {
            envelopeSchema = Path.of("contracts/src/main/resources/schemas/common/EventEnvelope.v1.json");
        }
        assertThat(envelopeSchema).exists();

        JsonNode root = MAPPER.readTree(envelopeSchema.toFile());
        JsonNode props = root.get("properties");
        assertThat(props).isNotNull();

        for (RecordComponent component : EventEnvelope.class.getRecordComponents()) {
            assertThat(props.has(component.getName()))
                    .withFailMessage("EventEnvelope.v1.json missing property for component '%s'", component.getName())
                    .isTrue();
            JsonAlias alias = component.getAnnotation(JsonAlias.class);
            if (alias != null) {
                for (String aliasName : alias.value()) {
                    assertThat(props.has(aliasName))
                            .withFailMessage(
                                    "EventEnvelope.v1.json missing property for @JsonAlias '%s' on '%s'",
                                    aliasName, component.getName())
                            .isTrue();
                }
            }
        }

        Set<String> anyOfRequired = StreamSupport.stream(root.get("anyOf").spliterator(), false)
                .flatMap(node -> StreamSupport.stream(node.get("required").spliterator(), false))
                .map(JsonNode::asText)
                .collect(Collectors.toSet());
        assertThat(anyOfRequired).containsExactlyInAnyOrder("source_service", "sourceService");
    }

    @Test
    @DisplayName("TopicNames adheres to ADR-003 .DLT naming convention across all domain event topics")
    void topicNamesMustFollowDeadLetterConvention() throws IllegalAccessException {
        Map<String, String> constants = new HashMap<>();
        for (Field field : TopicNames.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (Modifier.isPublic(modifiers)
                    && Modifier.isStatic(modifiers)
                    && Modifier.isFinal(modifiers)
                    && field.getType().equals(String.class)) {
                constants.put(field.getName(), (String) field.get(null));
            }
        }

        Set<String> allValues = Set.copyOf(constants.values());
        for (Map.Entry<String, String> entry : constants.entrySet()) {
            String value = entry.getValue();
            if (value.endsWith(".events") && !entry.getKey().equals("ORDER_EVENTS") && !entry.getKey().equals("PAYMENT_EVENTS")) {
                assertThat(allValues)
                        .withFailMessage("Missing .DLT constant for domain topic %s (%s)", entry.getKey(), value)
                        .contains(value + TopicNames.DLT_SUFFIX);
            }
            if (value.endsWith(".dlq")) {
                assertThat(value)
                        .withFailMessage("ADR-003 forbids .dlq suffix for domain topics; found %s=%s", entry.getKey(), value)
                        .isEqualTo(TopicNames.OUTBOX_RELAY_DLQ);
            }
        }
    }
}
