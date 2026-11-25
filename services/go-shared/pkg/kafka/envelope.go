package kafka

import (
	"errors"
	"fmt"
	"regexp"
	"strings"
	"time"
	"unicode/utf8"
)

// ErrInvalidSchemaVersion indicates an event schema version fails contract formatting rules.
var ErrInvalidSchemaVersion = errors.New("invalid schema version")

var schemaVersionPattern = regexp.MustCompile(`^(v[1-9][0-9]*|[0-9]+\.[0-9]+\.[0-9]+)$`)

// EventEnvelope represents the canonical ADR-004 Kafka event envelope.
type EventEnvelope struct {
	ID            string    `json:"id"`
	EventID       string    `json:"eventId"`
	EventType     string    `json:"eventType"`
	AggregateID   string    `json:"aggregateId"`
	AggregateType string    `json:"aggregateType"`
	SchemaVersion string    `json:"schemaVersion"`
	SourceService string    `json:"sourceService"`
	CorrelationID string    `json:"correlationId"`
	Timestamp     time.Time `json:"timestamp"`
	EventTime     time.Time `json:"eventTime"`
	Payload       any       `json:"payload"`
}

// ValidateSchemaVersion validates raw against vN or semver (X.Y.Z), defaulting empty values to "v1".
func ValidateSchemaVersion(raw string) (string, error) {
	v := strings.TrimSpace(raw)
	if v == "" {
		return "v1", nil
	}
	if !schemaVersionPattern.MatchString(v) {
		return "", fmt.Errorf("%w: %q", ErrInvalidSchemaVersion, v)
	}
	return v, nil
}

// Validate verifies required envelope metadata and normalizes SchemaVersion.
func (e *EventEnvelope) Validate() error {
	if e == nil {
		return errors.New("kafka: event envelope must not be nil")
	}
	e.syncAliases()
	if strings.TrimSpace(e.EventID) == "" {
		return errors.New("kafka: eventId is required")
	}
	if strings.TrimSpace(e.EventType) == "" {
		return errors.New("kafka: eventType is required")
	}
	if strings.TrimSpace(e.AggregateID) == "" {
		return errors.New("kafka: aggregateId is required")
	}
	if strings.TrimSpace(e.AggregateType) == "" {
		return errors.New("kafka: aggregateType is required")
	}
	ver, err := ValidateSchemaVersion(e.SchemaVersion)
	if err != nil {
		return err
	}
	e.SchemaVersion = ver
	return nil
}

// TruncateUTF8 truncates value to at most maxBytes without splitting a multi-byte UTF-8 rune.
func TruncateUTF8(value string, maxBytes int) string {
	if maxBytes <= 0 || len(value) <= maxBytes {
		return value
	}
	for maxBytes > 0 && !utf8.RuneStart(value[maxBytes]) {
		maxBytes--
	}
	return value[:maxBytes]
}
