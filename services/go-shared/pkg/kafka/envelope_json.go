package kafka

import (
	"encoding/json"
	"errors"
	"strings"
	"time"
)

func (e *EventEnvelope) syncAliases() {
	if e == nil {
		return
	}
	if strings.TrimSpace(e.ID) == "" && strings.TrimSpace(e.EventID) != "" {
		e.ID = e.EventID
	}
	if strings.TrimSpace(e.EventID) == "" && strings.TrimSpace(e.ID) != "" {
		e.EventID = e.ID
	}
	if e.EventTime.IsZero() && !e.Timestamp.IsZero() {
		e.EventTime = e.Timestamp
	}
	if e.Timestamp.IsZero() && !e.EventTime.IsZero() {
		e.Timestamp = e.EventTime
	}
}

// MarshalJSON emits both id/eventId and eventTime/timestamp for contract compatibility across Java and Go services.
func (e EventEnvelope) MarshalJSON() ([]byte, error) {
	type envelopeAlias EventEnvelope
	normalized := e
	normalized.syncAliases()
	return json.Marshal(envelopeAlias(normalized))
}

// UnmarshalJSON accepts id/eventId/event_id and eventTime/timestamp/event_time aliases and keeps fields synchronized.
func (e *EventEnvelope) UnmarshalJSON(data []byte) error {
	if e == nil {
		return errors.New("kafka: event envelope must not be nil")
	}
	type envelopeAlias EventEnvelope
	type wireEnvelope struct {
		envelopeAlias
		LegacyEventID       string    `json:"event_id"`
		LegacyEventTime     time.Time `json:"event_time"`
		LegacySourceService string    `json:"source_service"`
		LegacyCorrelationID string    `json:"correlation_id"`
		LegacyAggregateID   string    `json:"aggregate_id"`
		LegacyAggregateType string    `json:"aggregate_type"`
		LegacyEventType     string    `json:"event_type"`
		LegacySchemaVersion string    `json:"schema_version"`
	}
	var w wireEnvelope
	if err := json.Unmarshal(data, &w); err != nil {
		return err
	}
	*e = EventEnvelope(w.envelopeAlias)
	if strings.TrimSpace(e.ID) == "" && strings.TrimSpace(w.LegacyEventID) != "" {
		e.ID = w.LegacyEventID
	}
	if strings.TrimSpace(e.EventID) == "" && strings.TrimSpace(w.LegacyEventID) != "" {
		e.EventID = w.LegacyEventID
	}
	if e.EventTime.IsZero() && !w.LegacyEventTime.IsZero() {
		e.EventTime = w.LegacyEventTime
	}
	if e.Timestamp.IsZero() && !w.LegacyEventTime.IsZero() {
		e.Timestamp = w.LegacyEventTime
	}
	if strings.TrimSpace(e.SourceService) == "" && strings.TrimSpace(w.LegacySourceService) != "" {
		e.SourceService = w.LegacySourceService
	}
	if strings.TrimSpace(e.CorrelationID) == "" && strings.TrimSpace(w.LegacyCorrelationID) != "" {
		e.CorrelationID = w.LegacyCorrelationID
	}
	if strings.TrimSpace(e.AggregateID) == "" && strings.TrimSpace(w.LegacyAggregateID) != "" {
		e.AggregateID = w.LegacyAggregateID
	}
	if strings.TrimSpace(e.AggregateType) == "" && strings.TrimSpace(w.LegacyAggregateType) != "" {
		e.AggregateType = w.LegacyAggregateType
	}
	if strings.TrimSpace(e.EventType) == "" && strings.TrimSpace(w.LegacyEventType) != "" {
		e.EventType = w.LegacyEventType
	}
	if strings.TrimSpace(e.SchemaVersion) == "" && strings.TrimSpace(w.LegacySchemaVersion) != "" {
		e.SchemaVersion = w.LegacySchemaVersion
	}
	e.syncAliases()
	return nil
}
