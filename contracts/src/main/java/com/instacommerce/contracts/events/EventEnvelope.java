package com.instacommerce.contracts.events;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

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
        @JsonAlias({"eventId", "event_id"})
        String id,

        @JsonAlias("event_type")
        String eventType,

        @JsonAlias("aggregate_type")
        String aggregateType,

        @JsonAlias("aggregate_id")
        String aggregateId,

        @JsonAlias({"event_time", "timestamp", "occurred_at", "event_timestamp"})
        Instant eventTime,

        @JsonAlias("schema_version")
        String schemaVersion,

        @JsonAlias({"source_service", "sourceService"})
        String sourceService,

        @JsonAlias({"correlation_id", "correlationId"})
        String correlationId,

        JsonNode payload
) {}
