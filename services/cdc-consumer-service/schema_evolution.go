package main

import (
	"context"
	"encoding/json"
	"log/slog"
	"sync"
	"time"

	"cloud.google.com/go/bigquery"
)

const defaultSchemaFailureTTL = 30 * time.Second

type schemaTable interface {
	TableName() string
	Metadata(ctx context.Context) (*bigquery.TableMetadata, error)
	Update(ctx context.Context, update bigquery.TableMetadataToUpdate, etag string, opts ...bigquery.TableUpdateOption) (*bigquery.TableMetadata, error)
}

type bqSchemaTable struct {
	table *bigquery.Table
}

func (t bqSchemaTable) TableName() string {
	if t.table == nil {
		return ""
	}
	return t.table.TableID
}

func (t bqSchemaTable) Metadata(ctx context.Context) (*bigquery.TableMetadata, error) {
	return t.table.Metadata(ctx)
}

func (t bqSchemaTable) Update(ctx context.Context, update bigquery.TableMetadataToUpdate, etag string, opts ...bigquery.TableUpdateOption) (*bigquery.TableMetadata, error) {
	return t.table.Update(ctx, update, etag, opts...)
}

// SchemaEvolutionHandler dynamically detects altered Postgres columns
// from Debezium CDC messages and evolves the BigQuery table schema.
type SchemaEvolutionHandler struct {
	table         schemaTable
	knownFields   sync.Map // map[string]bool
	logger        *slog.Logger
	mu            sync.Mutex
	failureTTL    time.Duration
	now           func() time.Time
	lastFailureAt time.Time
	lastFailure   error
}

// NewSchemaEvolutionHandler creates a new SchemaEvolutionHandler.
func NewSchemaEvolutionHandler(table *bigquery.Table, logger *slog.Logger) *SchemaEvolutionHandler {
	var st schemaTable
	if table != nil {
		st = bqSchemaTable{table: table}
	}
	return newSchemaEvolutionHandlerWithTable(st, logger, defaultSchemaFailureTTL)
}

func newSchemaEvolutionHandlerWithTable(table schemaTable, logger *slog.Logger, failureTTL time.Duration) *SchemaEvolutionHandler {
	if failureTTL <= 0 {
		failureTTL = defaultSchemaFailureTTL
	}
	return &SchemaEvolutionHandler{
		table:      table,
		logger:     logger,
		failureTTL: failureTTL,
		now:        time.Now,
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
	return ExtractCDCFieldsFromMap(raw), nil
}

// ExtractCDCFieldsFromMap extracts altered field candidates from an already-decoded CDC envelope.
func ExtractCDCFieldsFromMap(raw map[string]any) map[string]any {
	if len(raw) == 0 {
		return nil
	}
	if inner, ok := raw["payload"].(map[string]any); ok {
		if after, ok := inner["after"].(map[string]any); ok {
			return after
		}
		return inner
	}

	if after, ok := raw["after"].(map[string]any); ok {
		return after
	}

	return raw
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

	if !h.lastFailureAt.IsZero() && h.now().Sub(h.lastFailureAt) < h.failureTTL {
		return h.lastFailure
	}

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
		h.lastFailureAt = h.now()
		h.lastFailure = err
		if h.logger != nil {
			h.logger.Warn("unable to read BigQuery table metadata for schema evolution", "error", err)
		}
		return err
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
	}

	if len(filteredAdd) == 0 {
		h.lastFailureAt = time.Time{}
		h.lastFailure = nil
		return nil
	}

	evolvedSchema := make(bigquery.Schema, 0, len(md.Schema)+len(filteredAdd))
	evolvedSchema = append(evolvedSchema, md.Schema...)
	evolvedSchema = append(evolvedSchema, filteredAdd...)
	update := bigquery.TableMetadataToUpdate{
		Schema: evolvedSchema,
	}

	if h.logger != nil {
		h.logger.Info("evolving BigQuery table schema for altered Postgres columns",
			"table", h.table.TableName(),
			"added_fields", len(filteredAdd),
		)
	}

	_, err = h.table.Update(ctx, update, md.ETag, bigquery.WithAutoDetectSchema(true))
	if err != nil {
		h.lastFailureAt = h.now()
		h.lastFailure = err
		if h.logger != nil {
			h.logger.Warn("BigQuery schema update warning; continuing with tolerant inserter", "error", err)
		}
		return err
	}

	h.lastFailureAt = time.Time{}
	h.lastFailure = nil
	for _, f := range filteredAdd {
		h.knownFields.Store(f.Name, true)
	}

	if h.logger != nil {
		h.logger.Info("BigQuery table schema successfully evolved", "table", h.table.TableName())
	}
	return nil
}
