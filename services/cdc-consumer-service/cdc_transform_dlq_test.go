package main

import (
	"context"
	"io"
	"log/slog"
	"testing"
	"time"
	"unicode/utf8"

	"github.com/segmentio/kafka-go"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"go.opentelemetry.io/otel/trace/noop"
)

func TestTransformMessage_TombstonesPartitionKeyAndSchemaVersion(t *testing.T) {
	tests := []struct {
		name          string
		msg           kafka.Message
		wantErr       bool
		wantErrTarget error
		wantTombstone bool
		wantOp        string
		wantKey       string
		wantSchema    string
	}{
		{
			name: "empty value tombstone emits valid delete row",
			msg: kafka.Message{
				Topic: "orders.cdc", Partition: 1, Offset: 10,
				Key: []byte("ord-tomb-1"), Value: nil, Time: time.Now().UTC(),
			},
			wantTombstone: true,
			wantOp:        "d",
			wantKey:       "ord-tomb-1",
			wantSchema:    "v1",
		},
		{
			name: "json null tombstone emits valid delete row",
			msg: kafka.Message{
				Topic: "orders.cdc", Partition: 1, Offset: 11,
				Key: []byte("ord-tomb-2"), Value: []byte("null"), Time: time.Now().UTC(),
			},
			wantTombstone: true,
			wantOp:        "d",
			wantKey:       "ord-tomb-2",
			wantSchema:    "v1",
		},
		{
			name: "extracts fallback partition key from after.order_id",
			msg: kafka.Message{
				Topic: "orders.cdc", Partition: 0, Offset: 12,
				Value:   []byte(`{"op":"c","schema_version":"v2","after":{"order_id":"ord-fallback-9"}}`),
				Headers: []kafka.Header{{Key: "schema_version", Value: []byte("v2")}},
			},
			wantTombstone: false,
			wantOp:        "c",
			wantKey:       "ord-fallback-9",
			wantSchema:    "v2",
		},
		{
			name: "missing partition key everywhere returns error",
			msg: kafka.Message{
				Topic: "orders.cdc", Partition: 0, Offset: 13,
				Value: []byte(`{"op":"u","after":{"status":"CONFIRMED"}}`),
			},
			wantErr:       true,
			wantErrTarget: errMissingPartitionKey,
		},
		{
			name: "invalid schema version rejected",
			msg: kafka.Message{
				Topic: "orders.cdc", Partition: 0, Offset: 14,
				Key:     []byte("ord-bad-ver"),
				Value:   []byte(`{"op":"u","after":{"id":"ord-bad-ver"}}`),
				Headers: []kafka.Header{{Key: "schema_version", Value: []byte("broken-schema")}},
			},
			wantErr:       true,
			wantErrTarget: errInvalidSchemaVersion,
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			saver, err := transformMessage(tc.msg)
			if tc.wantErr {
				require.Error(t, err)
				if tc.wantErrTarget != nil {
					assert.ErrorIs(t, err, tc.wantErrTarget)
				}
				return
			}
			require.NoError(t, err)
			vals, _, err := saver.Save()
			require.NoError(t, err)
			assert.Equal(t, tc.wantTombstone, vals["tombstone"])
			assert.Equal(t, tc.wantOp, vals["op"])
			assert.Equal(t, tc.wantKey, vals["partition_key"])
			assert.Equal(t, tc.wantSchema, vals["schema_version"])
		})
	}
}

func TestDLQMetadataEnrichmentAndUTF8Truncate(t *testing.T) {
	items := []batchItem{{
		message: kafka.Message{
			Topic:     "orders.cdc",
			Partition: 2,
			Offset:    42,
			Key:       []byte("ord-1"),
			Value:     []byte("bad"),
			Headers:   []kafka.Header{{Key: "schema_version", Value: []byte("v3")}},
		},
	}}

	msgs := buildDLQKafkaMessages(items, "schema bad", "schema_version_invalid", "cdc-grp", "2026-11-19T12:00:00Z", "trace123", "00-trace123-span-01")
	require.Len(t, msgs, 1)
	hm := headersToMap(msgs[0].Headers)
	assert.Equal(t, "schema_version_invalid", hm["error_category"])
	assert.Equal(t, "cdc-grp", hm["consumer_group"])
	assert.Equal(t, "v3", hm["schema_version"])
	assert.Equal(t, "2026-11-19T12:00:00Z", hm["quarantined_at"])
	assert.Equal(t, "orders.cdc", hm["original_topic"])

	schemaVerHeaders := 0
	for _, h := range msgs[0].Headers {
		if h.Key == "schema_version" {
			schemaVerHeaders++
		}
	}
	assert.Equal(t, 1, schemaVerHeaders)

	err := sendToDLQ(
		context.Background(),
		nil,
		time.Second,
		Metrics{},
		slog.New(slog.NewTextHandler(io.Discard, nil)),
		noop.NewTracerProvider().Tracer("test"),
		items,
		errInvalidSchemaVersion,
		"cdc-grp",
	)
	require.Error(t, err)

	s := "hello-🚀-world"
	truncated := truncate(s, 8)
	assert.True(t, utf8.ValidString(truncated))
	assert.Equal(t, "hello-", truncated)
}
