package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"time"

	"github.com/IBM/sarama"
	sharedkafka "github.com/instacommerce/go-shared/pkg/kafka"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/trace"
)

type outboxEvent struct {
	ID            string
	AggregateType string
	AggregateID   string
	EventType     string
	Payload       []byte
	CreatedAt     time.Time
	SourceService string
	CorrelationID string
	SchemaVersion string
	Traceparent   string
}

var (
	errMissingPartitionKey  = errors.New("aggregate partition key missing")
	errInvalidSchemaVersion = sharedkafka.ErrInvalidSchemaVersion
	errInvalidOutboxPayload = errors.New("invalid outbox payload JSON")
	topicMappings           = map[string]string{
		"order": "orders.events", "orders": "orders.events",
		"payment": "payments.events", "payments": "payments.events",
		"inventory": "inventory.events", "stockitem": "inventory.events", "stock_item": "inventory.events",
		"reservation": "inventory.events", "reservations": "inventory.events",
		"cyclecounttask": "inventory.events", "cycle_count_task": "inventory.events",
		"inbound_asn": "inventory.events", "inboundasn": "inventory.events",
		"stock_batch": "inventory.events", "stockbatch": "inventory.events",
		"inventory_safety_stock": "inventory.events", "inventorysafetystock": "inventory.events",
		"fulfillment": "fulfillment.events", "picktask": "fulfillment.events", "pick_task": "fulfillment.events",
		"pickbatch": "fulfillment.events", "pick_batch": "fulfillment.events",
		"deliveryfailure": "fulfillment.events", "delivery_failure": "fulfillment.events",
		"substitution": "fulfillment.events", "substitutions": "fulfillment.events",
		"catalog": "catalog.events", "product": "catalog.events", "products": "catalog.events",
		"category": "catalog.events", "categories": "catalog.events",
		"pricing": "pricing.events", "price": "pricing.events", "coupon": "pricing.events",
		"coupons": "pricing.events", "promotion": "pricing.events", "promotions": "pricing.events",
		"identity": "identity.events", "user": "identity.events", "users": "identity.events",
		"rider": "rider.events", "riders": "rider.events", "riderfleet": "rider.events", "rider_fleet": "rider.events",
		"geofence": "rider.geofence.events", "rider_geofence": "rider.geofence.events", "ridergeofence": "rider.geofence.events",
		"warehouse": "warehouse.events", "store": "warehouse.events", "stores": "warehouse.events",
		"warehousebin": "warehouse.events", "warehouse_bin": "warehouse.events",
		"storecapacity": "warehouse.events", "store_capacity": "warehouse.events",
		"stagingbay": "warehouse.events", "staging_bay": "warehouse.events",
		"fraud": "fraud.events", "fraudsignal": "fraud.events", "fraud_signal": "fraud.events",
		"wallet": "wallet.events", "wallets": "wallet.events", "loyalty": "wallet.events",
		"audit": "audit.events", "auditevent": "audit.events", "audit_event": "audit.events",
		"notification": "notifications.events", "notifications": "notifications.events",
		"config": "config.events", "featureflag": "config.events", "feature_flag": "config.events",
		"featureflags": "config.events", "feature_flags": "config.events",
		"cart": "cart.events", "carts": "cart.events",
		"delivery": "delivery.events", "routing": "delivery.events",
	}
)

func resolvePartitionKey(evt outboxEvent) (string, error) {
	if trimmed := strings.TrimSpace(evt.AggregateID); trimmed != "" {
		return trimmed, nil
	}
	return "", fmt.Errorf("%w: aggregate_id is empty for event %s", errMissingPartitionKey, evt.ID)
}

func validateSchemaVersion(raw string) (string, error) {
	return sharedkafka.ValidateSchemaVersion(raw)
}

func validateOutboxEvent(evt outboxEvent) (partitionKey, schemaVersion string, err error) {
	partitionKey, err = resolvePartitionKey(evt)
	if err != nil {
		return "", "", err
	}
	schemaVersion, err = validateSchemaVersion(evt.SchemaVersion)
	if err != nil {
		return partitionKey, "", err
	}
	var parsed any
	if unmarshalErr := json.Unmarshal(evt.Payload, &parsed); unmarshalErr != nil {
		return partitionKey, schemaVersion, fmt.Errorf("%w: %v", errInvalidOutboxPayload, unmarshalErr)
	}
	return partitionKey, schemaVersion, nil
}

func classifyRelayError(err error) string {
	switch {
	case errors.Is(err, errMissingPartitionKey):
		return "partition_key_missing"
	case errors.Is(err, errInvalidSchemaVersion):
		return "schema_version_invalid"
	case errors.Is(err, errInvalidOutboxPayload):
		return "payload_invalid"
	default:
		return "publish_failure"
	}
}

func isValidTraceparent(tp string) (trace.SpanContext, bool) {
	tp = strings.TrimSpace(tp)
	if tp == "" {
		return trace.SpanContext{}, false
	}
	extracted := (propagation.TraceContext{}).Extract(context.Background(), propagation.MapCarrier{"traceparent": tp})
	sc := trace.SpanContextFromContext(extracted)
	return sc, sc.IsValid()
}

func upsertSaramaHeader(headers []sarama.RecordHeader, h sarama.RecordHeader) []sarama.RecordHeader {
	key := string(h.Key)
	for i := range headers {
		if string(headers[i].Key) == key {
			headers[i].Value = h.Value
			return headers
		}
	}
	return append(headers, h)
}

func buildSaramaHeaders(evt outboxEvent, traceID string, extra ...sarama.RecordHeader) []sarama.RecordHeader {
	headers := []sarama.RecordHeader{
		{Key: []byte("event_id"), Value: []byte(evt.ID)},
		{Key: []byte("event_type"), Value: []byte(evt.EventType)},
		{Key: []byte("aggregate_type"), Value: []byte(evt.AggregateType)},
		{Key: []byte("aggregate_id"), Value: []byte(evt.AggregateID)},
		{Key: []byte("partition_key"), Value: []byte(strings.TrimSpace(evt.AggregateID))},
		{Key: []byte("source_service"), Value: []byte(evt.SourceService)},
		{Key: []byte("correlation_id"), Value: []byte(evt.CorrelationID)},
		{Key: []byte("X-Correlation-Id"), Value: []byte(evt.CorrelationID)},
		{Key: []byte("X-Source-Service"), Value: []byte(evt.SourceService)},
	}
	if evt.SchemaVersion != "" {
		headers = append(headers, sarama.RecordHeader{Key: []byte("schema_version"), Value: []byte(evt.SchemaVersion)})
	}
	for _, h := range extra {
		headers = upsertSaramaHeader(headers, h)
	}
	if sc, ok := isValidTraceparent(evt.Traceparent); ok {
		headers = upsertSaramaHeader(headers, sarama.RecordHeader{Key: []byte("traceparent"), Value: []byte(strings.TrimSpace(evt.Traceparent))})
		if traceID == "" {
			traceID = sc.TraceID().String()
		}
	}
	if traceID != "" {
		headers = upsertSaramaHeader(headers, sarama.RecordHeader{Key: []byte("X-Trace-Id"), Value: []byte(traceID)})
		headers = upsertSaramaHeader(headers, sarama.RecordHeader{Key: []byte("X-B3-TraceId"), Value: []byte(traceID)})
	}
	return headers
}

func resolveKafkaTopic(overrideTopic, aggregateType string) string {
	if trimmed := strings.TrimSpace(overrideTopic); trimmed != "" {
		return trimmed
	}
	normalized := normalizeAggregateType(aggregateType)
	if topic, ok := topicMappings[normalized]; ok {
		return topic
	}
	if normalized == "" {
		return "unknown.events"
	}
	return normalized + ".events"
}

func normalizeAggregateType(raw string) string {
	raw = strings.TrimSpace(strings.ToLower(raw))
	if raw == "" {
		return ""
	}
	var b strings.Builder
	b.Grow(len(raw))
	lastWasUnderscore := false
	for _, r := range raw {
		switch {
		case (r >= 'a' && r <= 'z') || (r >= '0' && r <= '9'):
			b.WriteRune(r)
			lastWasUnderscore = false
		default:
			if b.Len() > 0 && !lastWasUnderscore {
				b.WriteByte('_')
				lastWasUnderscore = true
			}
		}
	}
	return strings.Trim(b.String(), "_")
}

func buildEventMessage(evt outboxEvent) []byte {
	var payload any
	if err := json.Unmarshal(evt.Payload, &payload); err != nil {
		return evt.Payload
	}

	schemaVersion := strings.TrimSpace(evt.SchemaVersion)
	if schemaVersion == "" {
		schemaVersion = "v1"
	}

	eventTime := evt.CreatedAt.UTC().Format(time.RFC3339Nano)
	envelope := map[string]any{
		"eventId":        evt.ID,
		"event_id":       evt.ID,
		"aggregateType":  evt.AggregateType,
		"aggregate_type": evt.AggregateType,
		"aggregateId":    evt.AggregateID,
		"aggregate_id":   evt.AggregateID,
		"eventType":      evt.EventType,
		"event_type":     evt.EventType,
		"eventTime":      eventTime,
		"event_time":     eventTime,
		"schemaVersion":  schemaVersion,
		"schema_version": schemaVersion,
		"source_service": evt.SourceService,
		"sourceService":  evt.SourceService,
		"payload":        payload,
	}
	if evt.CorrelationID != "" {
		envelope["correlation_id"] = evt.CorrelationID
		envelope["correlationId"] = evt.CorrelationID
	}
	if payloadMap, ok := payload.(map[string]any); ok {
		for key, value := range payloadMap {
			if _, exists := envelope[key]; !exists {
				envelope[key] = value
			}
		}
	}
	if _, exists := envelope["id"]; !exists {
		envelope["id"] = evt.ID
	}
	if _, exists := envelope["timestamp"]; !exists {
		envelope["timestamp"] = eventTime
	}

	bytes, err := json.Marshal(envelope)
	if err != nil {
		return evt.Payload
	}
	return bytes
}

func errMessage(err error) string {
	if err == nil {
		return "unknown error"
	}
	return err.Error()
}

func resolveDLQSchemaVersion(evt outboxEvent, schemaVersion string) string {
	if trimmed := strings.TrimSpace(schemaVersion); trimmed != "" {
		return trimmed
	}
	if trimmed := strings.TrimSpace(evt.SchemaVersion); trimmed != "" {
		return trimmed
	}
	return "unknown"
}

func buildEnrichedDLQMessage(evt outboxEvent, targetTopic, partitionKey string, attempts int, produceErr error, quarantinedAt time.Time) []byte {
	var payload any
	if err := json.Unmarshal(evt.Payload, &payload); err != nil {
		payload = string(evt.Payload)
	}
	if attempts < 1 {
		attempts = 1
	}

	dlqEnvelope := map[string]any{
		"eventId":        evt.ID,
		"originalTopic":  targetTopic,
		"partitionKey":   partitionKey,
		"error":          errMessage(produceErr),
		"errorCategory":  classifyRelayError(produceErr),
		"attempts":       attempts,
		"timestamp":      quarantinedAt.UTC().Format(time.RFC3339Nano),
		"quarantinedAt":  quarantinedAt.UTC().Format(time.RFC3339Nano),
		"eventCreatedAt": evt.CreatedAt.UTC().Format(time.RFC3339Nano),
		"aggregateType":  evt.AggregateType,
		"aggregateId":    evt.AggregateID,
		"eventType":      evt.EventType,
		"schemaVersion":  resolveDLQSchemaVersion(evt, ""),
		"sourceService":  evt.SourceService,
		"correlationId":  evt.CorrelationID,
		"payload":        payload,
	}

	bytes, err := json.Marshal(dlqEnvelope)
	if err != nil {
		return evt.Payload
	}
	return bytes
}

func buildDLQHeaders(evt outboxEvent, targetTopic, traceID, partitionKey, schemaVersion string, attempts int, err error, quarantinedAt time.Time) []sarama.RecordHeader {
	if attempts < 1 {
		attempts = 1
	}
	resolvedSchema := resolveDLQSchemaVersion(evt, schemaVersion)
	reason := sharedkafka.TruncateUTF8(errMessage(err), 512)
	return buildSaramaHeaders(evt, traceID,
		sarama.RecordHeader{Key: []byte("schema_version"), Value: []byte(resolvedSchema)},
		sarama.RecordHeader{Key: []byte("partition_key"), Value: []byte(partitionKey)},
		sarama.RecordHeader{Key: []byte("original_topic"), Value: []byte(targetTopic)},
		sarama.RecordHeader{Key: []byte("dlq_reason"), Value: []byte(reason)},
		sarama.RecordHeader{Key: []byte("error_category"), Value: []byte(classifyRelayError(err))},
		sarama.RecordHeader{Key: []byte("quarantine_attempts"), Value: []byte(strconv.Itoa(attempts))},
		sarama.RecordHeader{Key: []byte("quarantined_at"), Value: []byte(quarantinedAt.UTC().Format(time.RFC3339Nano))},
	)
}
