package main

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"cloud.google.com/go/bigquery"
	sharedkafka "github.com/instacommerce/go-shared/pkg/kafka"
	"github.com/segmentio/kafka-go"
)

var (
	errMissingPartitionKey  = errors.New("cdc: aggregate partition key missing")
	errInvalidSchemaVersion = sharedkafka.ErrInvalidSchemaVersion
)

type bqRow struct {
	values   map[string]bigquery.Value
	insertID string
}

func (r bqRow) Save() (map[string]bigquery.Value, string, error) {
	return r.values, r.insertID, nil
}

func isTombstonePayload(val []byte) bool {
	trimmed := bytes.TrimSpace(val)
	return len(trimmed) == 0 || bytes.Equal(trimmed, []byte("null"))
}

func validateSchemaVersion(raw string) (string, error) {
	return sharedkafka.ValidateSchemaVersion(raw)
}

func extractHeaderValue(headers []kafka.Header, keys ...string) string {
	for _, wanted := range keys {
		for _, h := range headers {
			if strings.EqualFold(h.Key, wanted) {
				if val := strings.TrimSpace(bytesToString(h.Value)); val != "" {
					return val
				}
			}
		}
	}
	return ""
}

func extractSchemaVersion(msg kafka.Message, payload map[string]any) (string, error) {
	if hv := extractHeaderValue(msg.Headers, "schema_version", "schemaVersion"); hv != "" {
		return validateSchemaVersion(hv)
	}
	for _, key := range []string{"schema_version", "schemaVersion"} {
		if raw, ok := payload[key].(string); ok && strings.TrimSpace(raw) != "" {
			return validateSchemaVersion(raw)
		}
	}
	if inner, ok := payload["payload"].(map[string]any); ok {
		for _, key := range []string{"schema_version", "schemaVersion"} {
			if raw, ok := inner[key].(string); ok && strings.TrimSpace(raw) != "" {
				return validateSchemaVersion(raw)
			}
		}
	}
	return "v1", nil
}

func extractPartitionKey(msg kafka.Message, root, inner map[string]any) (string, error) {
	if k := strings.TrimSpace(bytesToString(msg.Key)); k != "" {
		return k, nil
	}
	candidates := []map[string]any{inner, root}
	for _, src := range candidates {
		if src == nil {
			continue
		}
		for _, key := range []string{"aggregate_id", "aggregateId", "id"} {
			if v, ok := src[key].(string); ok && strings.TrimSpace(v) != "" {
				return strings.TrimSpace(v), nil
			}
		}
		for _, nestedKey := range []string{"after", "before"} {
			if sub, ok := src[nestedKey].(map[string]any); ok {
				for _, key := range []string{"aggregate_id", "id", "order_id", "payment_id"} {
					if v, ok := sub[key].(string); ok && strings.TrimSpace(v) != "" {
						return strings.TrimSpace(v), nil
					}
				}
			}
		}
	}
	return "", fmt.Errorf("%w for topic %s partition %d offset %d", errMissingPartitionKey, msg.Topic, msg.Partition, msg.Offset)
}

func transformMessage(msg kafka.Message) (bigquery.ValueSaver, error) {
	insertID := fmt.Sprintf("%s-%d-%d", msg.Topic, msg.Partition, msg.Offset)
	headersValue := mustJSONValue(headersToMap(msg.Headers))

	if isTombstonePayload(msg.Value) {
		partitionKey, err := extractPartitionKey(msg, nil, nil)
		if err != nil {
			return nil, err
		}
		schemaVersion, err := extractSchemaVersion(msg, nil)
		if err != nil {
			return nil, err
		}
		return bqRow{
			values: map[string]bigquery.Value{
				"topic":           msg.Topic,
				"partition":       msg.Partition,
				"offset":          msg.Offset,
				"key":             bytesToString(msg.Key),
				"partition_key":   partitionKey,
				"schema_version":  schemaVersion,
				"op":              "d",
				"tombstone":       true,
				"ts_ms":           nil,
				"source":          nil,
				"before":          nil,
				"after":           nil,
				"payload":         nil,
				"headers":         headersValue,
				"raw":             bytesToString(msg.Value),
				"kafka_timestamp": msg.Time,
				"ingested_at":     time.Now(),
			},
			insertID: insertID,
		}, nil
	}

	var payload map[string]any
	if err := json.Unmarshal(msg.Value, &payload); err != nil {
		return nil, err
	}
	return transformParsedMessage(msg, payload)
}

func transformParsedMessage(msg kafka.Message, payload map[string]any) (bigquery.ValueSaver, error) {
	insertID := fmt.Sprintf("%s-%d-%d", msg.Topic, msg.Partition, msg.Offset)
	headersValue := mustJSONValue(headersToMap(msg.Headers))

	var op string
	var tsValue, beforeValue, afterValue, sourceValue, payloadValue bigquery.Value
	var innerPayload map[string]any
	if wrapped, ok := payload["payload"].(map[string]any); ok {
		innerPayload = wrapped
		if opField, ok := wrapped["op"].(string); ok {
			op = opField
		}
		tsValue = parseTsMs(wrapped["ts_ms"])
		beforeValue = mustJSONValue(wrapped["before"])
		afterValue = mustJSONValue(wrapped["after"])
		sourceValue = mustJSONValue(wrapped["source"])
		payloadValue = mustJSONValue(wrapped)
	} else {
		if opField, ok := payload["op"].(string); ok {
			op = opField
		}
		tsValue = parseTsMs(payload["ts_ms"])
		beforeValue = mustJSONValue(payload["before"])
		afterValue = mustJSONValue(payload["after"])
		sourceValue = mustJSONValue(payload["source"])
		payloadValue = mustJSONValue(payload)
	}

	schemaVersion, err := extractSchemaVersion(msg, payload)
	if err != nil {
		return nil, err
	}
	partitionKey, err := extractPartitionKey(msg, payload, innerPayload)
	if err != nil {
		return nil, err
	}

	values := map[string]bigquery.Value{
		"topic":           msg.Topic,
		"partition":       msg.Partition,
		"offset":          msg.Offset,
		"key":             bytesToString(msg.Key),
		"partition_key":   partitionKey,
		"schema_version":  schemaVersion,
		"op":              op,
		"tombstone":       false,
		"ts_ms":           tsValue,
		"source":          sourceValue,
		"before":          beforeValue,
		"after":           afterValue,
		"payload":         payloadValue,
		"headers":         headersValue,
		"raw":             bytesToString(msg.Value),
		"kafka_timestamp": msg.Time,
		"ingested_at":     time.Now(),
	}
	return bqRow{values: values, insertID: insertID}, nil
}

func mustJSONValue(value any) bigquery.Value {
	if value == nil {
		return nil
	}
	data, err := json.Marshal(value)
	if err != nil {
		return nil
	}
	return string(data)
}

func parseTsMs(value any) bigquery.Value {
	switch v := value.(type) {
	case nil:
		return nil
	case float64:
		return int64(v)
	case int64:
		return v
	case int:
		return int64(v)
	case json.Number:
		if ts, err := v.Int64(); err == nil {
			return ts
		}
		return nil
	case string:
		if ts, err := strconv.ParseInt(v, 10, 64); err == nil {
			return ts
		}
		return nil
	default:
		return nil
	}
}

func headersToMap(headers []kafka.Header) map[string]string {
	if len(headers) == 0 {
		return nil
	}
	result := make(map[string]string, len(headers))
	for _, header := range headers {
		result[header.Key] = bytesToString(header.Value)
	}
	return result
}

func bytesToString(value []byte) string {
	if len(value) == 0 {
		return ""
	}
	if utf8.Valid(value) {
		return string(value)
	}
	return base64.StdEncoding.EncodeToString(value)
}
