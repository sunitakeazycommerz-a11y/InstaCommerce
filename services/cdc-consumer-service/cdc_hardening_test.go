package main

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"net"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"cloud.google.com/go/bigquery"
	"github.com/instacommerce/go-shared/pkg/health"
	"github.com/prometheus/client_golang/prometheus"
	"github.com/segmentio/kafka-go"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

type stubSchemaTable struct {
	name          string
	metadataCalls atomic.Int32
	updateCalls   atomic.Int32
	metadataErr   error
	updateErr     error
	schema        bigquery.Schema
}

func (s *stubSchemaTable) TableName() string {
	return s.name
}

func (s *stubSchemaTable) Metadata(context.Context) (*bigquery.TableMetadata, error) {
	s.metadataCalls.Add(1)
	if s.metadataErr != nil {
		return nil, s.metadataErr
	}
	return &bigquery.TableMetadata{Schema: s.schema, ETag: "etag-1"}, nil
}

func (s *stubSchemaTable) Update(_ context.Context, _ bigquery.TableMetadataToUpdate, _ string, _ ...bigquery.TableUpdateOption) (*bigquery.TableMetadata, error) {
	s.updateCalls.Add(1)
	if s.updateErr != nil {
		return nil, s.updateErr
	}
	return &bigquery.TableMetadata{Schema: s.schema, ETag: "etag-2"}, nil
}

func TestSchemaEvolutionHandler_CachesFailuresDuringCooldown(t *testing.T) {
	now := time.Date(2026, time.March, 15, 12, 0, 0, 0, time.UTC)
	tbl := &stubSchemaTable{
		name:        "orders_cdc",
		metadataErr: errors.New("bq metadata quota exceeded"),
	}
	h := newSchemaEvolutionHandlerWithTable(tbl, slog.New(slog.NewTextHandler(io.Discard, nil)), 30*time.Second)
	h.now = func() time.Time { return now }

	fields := map[string]any{"new_col": "val"}

	err1 := h.DetectAndEvolveSchema(context.Background(), fields)
	require.Error(t, err1)
	assert.Equal(t, int32(1), tbl.metadataCalls.Load())

	now = now.Add(10 * time.Second)
	err2 := h.DetectAndEvolveSchema(context.Background(), fields)
	require.Error(t, err2)
	assert.Equal(t, int32(1), tbl.metadataCalls.Load(), "expected failure cache to suppress repeated Metadata calls within TTL")

	now = now.Add(25 * time.Second)
	tbl.metadataErr = nil
	tbl.schema = bigquery.Schema{{Name: "existing_col", Type: bigquery.StringFieldType}}
	err3 := h.DetectAndEvolveSchema(context.Background(), fields)
	require.NoError(t, err3)
	assert.Equal(t, int32(2), tbl.metadataCalls.Load())
	assert.Equal(t, int32(1), tbl.updateCalls.Load())

	err4 := h.DetectAndEvolveSchema(context.Background(), fields)
	require.NoError(t, err4)
	assert.Equal(t, int32(2), tbl.metadataCalls.Load(), "known fields should not re-fetch metadata")
}

func TestProcessMessageForConsumer_SingleUnmarshalAndEvolution(t *testing.T) {
	tbl := &stubSchemaTable{
		name:   "orders_cdc",
		schema: bigquery.Schema{{Name: "id", Type: bigquery.StringFieldType}},
	}
	h := newSchemaEvolutionHandlerWithTable(tbl, slog.New(slog.NewTextHandler(io.Discard, nil)), 30*time.Second)
	metrics := Metrics{schemaEvolutionErrors: prometheus.NewCounter(prometheus.CounterOpts{Name: "test_cdc_single_unmarshal_err"})}

	msg := kafka.Message{
		Topic:     "orders.cdc",
		Partition: 0,
		Offset:    7,
		Key:       []byte("ord-7"),
		Value:     []byte(`{"payload":{"op":"c","ts_ms":1700000000000,"after":{"id":"ord-7","delivery_tip":25}}}`),
	}

	row, err := processMessageForConsumer(context.Background(), msg, h, metrics, slog.New(slog.NewTextHandler(io.Discard, nil)), nil)
	require.NoError(t, err)
	require.NotNil(t, row)
	assert.Equal(t, int32(1), tbl.metadataCalls.Load())
	assert.Equal(t, int32(1), tbl.updateCalls.Load())

	_, invalidErr := processMessageForConsumer(context.Background(), kafka.Message{
		Topic: "orders.cdc",
		Value: []byte(`{invalid-json`),
	}, h, metrics, slog.New(slog.NewTextHandler(io.Discard, nil)), nil)
	require.Error(t, invalidErr)
}

func TestStartHTTPServerWithErr_ReportsBindFailure(t *testing.T) {
	ln, err := net.Listen("tcp", ":0")
	require.NoError(t, err)
	defer ln.Close()

	_, port, err := net.SplitHostPort(ln.Addr().String())
	require.NoError(t, err)

	errCh := make(chan error, 1)
	srv := startHTTPServerWithErr(port, health.NewHandler(), slog.New(slog.NewTextHandler(io.Discard, nil)), func(err error) {
		select {
		case errCh <- err:
		default:
		}
	})
	defer func() {
		_ = srv.Close()
	}()

	select {
	case listenErr := <-errCh:
		require.Error(t, listenErr)
		assert.True(t, strings.Contains(listenErr.Error(), "address already in use") || strings.Contains(listenErr.Error(), "bind"), "unexpected bind error: %v", listenErr)
	case <-time.After(3 * time.Second):
		t.Fatal("timed out waiting for HTTP bind failure on errCh")
	}
}
