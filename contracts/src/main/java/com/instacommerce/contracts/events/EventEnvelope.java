package com.instacommerce.contracts.events;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.Map;

/**
 * Canonical event envelope that wraps every domain event published on Kafka.
 *
 * <p>Supports both camelCase and snake_case JSON field names for backwards
 * compatibility with producers that use either convention.
 *
 * @see contracts/src/main/resources/schemas/common/EventEnvelope.v1.json
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventEnvelope(
        @JsonAlias("eventId")
        String id,

        String eventType,

        String aggregateType,

        @JsonAlias("aggregate_id")
        String aggregateId,

        @JsonAlias("event_time")
        Instant eventTime,

        @JsonAlias("schema_version")
        String schemaVersion,

        @JsonAlias("source_service")
        String sourceService,

        @JsonAlias("correlation_id")
        String correlationId,

        Map<String, Object> payload
) {}
