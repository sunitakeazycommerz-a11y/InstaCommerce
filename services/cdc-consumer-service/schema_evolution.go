package main

import (
	"context"
	"encoding/json"
	"log/slog"
	"sync"
	"time"

	"cloud.google.com/go/bigquery"
)

// SchemaEvolutionHandler dynamically detects altered Postgres columns
// from Debezium CDC messages and evolves the BigQuery table schema.
type SchemaEvolutionHandler struct {
	table       *bigquery.Table
	knownFields sync.Map // map[string]bool
	logger      *slog.Logger
	mu          sync.Mutex
}

// NewSchemaEvolutionHandler creates a new SchemaEvolutionHandler.
func NewSchemaEvolutionHandler(table *bigquery.Table, logger *slog.Logger) *SchemaEvolutionHandler {
	return &SchemaEvolutionHandler{
		table:  table,
		logger: logger,
	}
}

// InferBigQueryType infers a BigQuery FieldType from a generic Go value.
func InferBigQueryType(val any) bigquery.FieldType {
	switch val.(type) {
	case bool:
		return bigquery.BooleanFieldType
	case float32, float64:
		return bigquery.FloatFieldType
	case int, int8, int16, int32, int64, uint, uint8, uint16, uint32, uint64:
		return bigquery.IntegerFieldType
	case string:
		return bigquery.StringFieldType
	case []byte:
		return bigquery.BytesFieldType
	case time.Time:
		return bigquery.TimestampFieldType
	case map[string]any, []any:
		return bigquery.JSONFieldType
	default:
		return bigquery.StringFieldType
	}
}

// ExtractCDCFields tolerantly parses Debezium messages, extracting the "after" fields
// and handling schema-wrapped, flat, and nested envelopes while ignoring unknown metadata.
func ExtractCDCFields(data []byte) (map[string]any, error) {
	if len(data) == 0 {
		return nil, nil
	}
	var raw map[string]any
	if err := json.Unmarshal(data, &raw); err != nil {
		return nil, err
	}

	// 1. Check for standard Debezium envelope with payload wrapper
	if inner, ok := raw["payload"].(map[string]any); ok {
		if after, ok := inner["after"].(map[string]any); ok {
			return after, nil
		}
		return inner, nil
	}

	// 2. Check for flat Debezium envelope
	if after, ok := raw["after"].(map[string]any); ok {
		return after, nil
	}

	return raw, nil
}

// DetectAndEvolveSchema checks for newly altered or added Postgres columns and
// updates the BigQuery table schema to allow field addition.
func (h *SchemaEvolutionHandler) DetectAndEvolveSchema(ctx context.Context, fields map[string]any) error {
	if h == nil || h.table == nil || len(fields) == 0 {
		return nil
	}

	var newFields []*bigquery.FieldSchema
	for name, val := range fields {
		if _, known := h.knownFields.Load(name); !known {
			newFields = append(newFields, &bigquery.FieldSchema{
				Name:     name,
				Type:     InferBigQueryType(val),
				Required: false, // Altered Postgres columns must be nullable
			})
		}
	}

	if len(newFields) == 0 {
		return nil
	}

	h.mu.Lock()
	defer h.mu.Unlock()

	// Double-check under lock
	var toAdd []*bigquery.FieldSchema
	for _, f := range newFields {
		if _, known := h.knownFields.Load(f.Name); !known {
			toAdd = append(toAdd, f)
		}
	}
	if len(toAdd) == 0 {
		return nil
	}

	md, err := h.table.Metadata(ctx)
	if err != nil {
		if h.logger != nil {
			h.logger.Warn("unable to read BigQuery table metadata for schema evolution", "error", err)
		}
		return nil
	}

	existing := make(map[string]bool)
	for _, f := range md.Schema {
		existing[f.Name] = true
		h.knownFields.Store(f.Name, true)
	}

	var filteredAdd []*bigquery.FieldSchema
	for _, f := range toAdd {
		if !existing[f.Name] {
			filteredAdd = append(filteredAdd, f)
			existing[f.Name] = true
		}
		h.knownFields.Store(f.Name, true)
	}

	if len(filteredAdd) == 0 {
		return nil
	}

	evolvedSchema := append(md.Schema, filteredAdd...)
	update := bigquery.TableMetadataToUpdate{
		Schema: evolvedSchema,
	}

	if h.logger != nil {
		h.logger.Info("evolving BigQuery table schema for altered Postgres columns",
			"table", h.table.TableID,
			"added_fields", len(filteredAdd),
		)
	}

	_, err = h.table.Update(ctx, update, md.ETag, bigquery.WithAutoDetectSchema(true))
	if err != nil {
		if h.logger != nil {
			h.logger.Warn("BigQuery schema update warning; continuing with tolerant inserter", "error", err)
		}
		return nil
	}

	if h.logger != nil {
		h.logger.Info("BigQuery table schema successfully evolved", "table", h.table.TableID)
	}
	return nil
}
