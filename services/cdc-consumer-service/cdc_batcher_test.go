package main

import (
	"context"
	"errors"
	"net/http"
	"sync"
	"testing"
	"time"

	"cloud.google.com/go/bigquery"
	"github.com/segmentio/kafka-go"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"google.golang.org/api/googleapi"
)

type fakeBQInserter struct {
	mu      sync.Mutex
	batches [][]bigquery.ValueSaver
	putFunc func(call int, rows []bigquery.ValueSaver) error
}

func (f *fakeBQInserter) Put(ctx context.Context, src any) error {
	rows, _ := src.([]bigquery.ValueSaver)
	f.mu.Lock()
	call := len(f.batches)
	copied := append([]bigquery.ValueSaver(nil), rows...)
	f.batches = append(f.batches, copied)
	fn := f.putFunc
	f.mu.Unlock()
	if fn != nil {
		return fn(call, copied)
	}
	return nil
}

type fakeDLQWriter struct {
	mu       sync.Mutex
	messages []kafka.Message
	err      error
}

func (w *fakeDLQWriter) WriteMessages(ctx context.Context, msgs ...kafka.Message) error {
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.err != nil {
		return w.err
	}
	w.messages = append(w.messages, msgs...)
	return nil
}

func TestLoadConfig_CDCConsumer(t *testing.T) {
	setRequired := func(t *testing.T) {
		t.Helper()
		t.Setenv("KAFKA_BROKERS", "localhost:9092, kafka-2:9092")
		t.Setenv("KAFKA_TOPICS", "orders.cdc, payments.cdc")
		t.Setenv("BQ_PROJECT", "instacommerce-analytics")
		t.Setenv("BQ_DATASET", "cdc_raw")
		t.Setenv("BQ_TABLE", "events")
	}

	t.Run("missing brokers fails", func(t *testing.T) {
		setRequired(t)
		t.Setenv("KAFKA_BROKERS", "")
		_, err := loadConfig()
		require.Error(t, err)
	})

	t.Run("missing topics fails", func(t *testing.T) {
		setRequired(t)
		t.Setenv("KAFKA_TOPICS", "")
		_, err := loadConfig()
		require.Error(t, err)
	})

	t.Run("missing bq coordinates fails", func(t *testing.T) {
		setRequired(t)
		t.Setenv("BQ_DATASET", "")
		_, err := loadConfig()
		require.Error(t, err)
	})

	t.Run("invalid log level fails", func(t *testing.T) {
		setRequired(t)
		t.Setenv("LOG_LEVEL", "verbose")
		_, err := loadConfig()
		require.Error(t, err)
	})

	t.Run("non-positive batch size fails", func(t *testing.T) {
		setRequired(t)
		t.Setenv("BQ_BATCH_SIZE", "0")
		_, err := loadConfig()
		require.Error(t, err)
	})

	t.Run("valid configuration with defaults", func(t *testing.T) {
		setRequired(t)
		t.Setenv("LOG_LEVEL", "warning")
		cfg, err := loadConfig()
		require.NoError(t, err)
		assert.Equal(t, []string{"localhost:9092", "kafka-2:9092"}, cfg.KafkaBrokers)
		assert.Equal(t, []string{"orders.cdc", "payments.cdc"}, cfg.KafkaTopics)
		assert.Equal(t, 500, cfg.BatchSize)
		assert.Equal(t, "cdc.dlq", cfg.KafkaDLQTopic)
	})
}

func TestInsertWithRetryDeps(t *testing.T) {
	cfg := Config{
		BQInsertTimeout: time.Second,
		BQMaxRetries:    2,
		BQBaseBackoff:   time.Millisecond,
		BQMaxBackoff:    5 * time.Millisecond,
	}

	t.Run("nil inserter returns error", func(t *testing.T) {
		_, err := insertWithRetryDeps(context.Background(), nil, nil, cfg, nil)
		require.Error(t, err)
	})

	t.Run("retries transient 503 then succeeds", func(t *testing.T) {
		inserter := &fakeBQInserter{
			putFunc: func(call int, rows []bigquery.ValueSaver) error {
				if call == 0 {
					return &googleapi.Error{Code: http.StatusServiceUnavailable, Message: "backend error"}
				}
				return nil
			},
		}
		failed, err := insertWithRetryDeps(context.Background(), inserter, []bigquery.ValueSaver{&bigquery.StructSaver{}}, cfg, nil)
		require.NoError(t, err)
		assert.Empty(t, failed)
		assert.Len(t, inserter.batches, 2)
	})

	t.Run("does not retry permanent 400 error", func(t *testing.T) {
		inserter := &fakeBQInserter{
			putFunc: func(call int, rows []bigquery.ValueSaver) error {
				return &googleapi.Error{Code: http.StatusBadRequest, Message: "invalid schema"}
			},
		}
		_, err := insertWithRetryDeps(context.Background(), inserter, []bigquery.ValueSaver{&bigquery.StructSaver{}}, cfg, nil)
		require.Error(t, err)
		assert.Len(t, inserter.batches, 1)
	})

	t.Run("returns failed row indices on PutMultiError", func(t *testing.T) {
		inserter := &fakeBQInserter{
			putFunc: func(call int, rows []bigquery.ValueSaver) error {
				return bigquery.PutMultiError{
					{RowIndex: 1, Errors: []error{errors.New("invalid row 1")}},
				}
			},
		}
		failed, err := insertWithRetryDeps(context.Background(), inserter, []bigquery.ValueSaver{&bigquery.StructSaver{}, &bigquery.StructSaver{}}, cfg, nil)
		require.NoError(t, err)
		assert.Equal(t, []int{1}, failed)
	})
}

func TestFlushBatchWithDeps(t *testing.T) {
	cfg := Config{
		KafkaGroupID:       "cdc-test-group",
		BQDataset:          "cdc_raw",
		BQTable:            "events",
		BQInsertTimeout:    time.Second,
		BQMaxRetries:       1,
		BQBaseBackoff:      time.Millisecond,
		BQMaxBackoff:       2 * time.Millisecond,
		DLQWriteTimeout:    time.Second,
		KafkaCommitTimeout: time.Second,
	}

	validMsg := func(key string, offset int64) batchItem {
		msg := kafka.Message{
			Topic:     "orders.cdc",
			Partition: 0,
			Offset:    offset,
			Key:       []byte(key),
			Value:     []byte(`{"op":"c","after":{"id":"` + key + `"}}`),
		}
		saver, err := transformMessage(msg)
		require.NoError(t, err)
		return batchItem{message: msg, row: saver}
	}

	t.Run("routes per-item transform errors and partial row failures to DLQ", func(t *testing.T) {
		items := []batchItem{
			{
				message:      kafka.Message{Topic: "orders.cdc", Partition: 0, Offset: 1, Value: []byte(`{}`)},
				transformErr: errMissingPartitionKey,
			},
			{
				message:      kafka.Message{Topic: "orders.cdc", Partition: 0, Offset: 2, Key: []byte("k2"), Value: []byte(`{}`)},
				transformErr: errInvalidSchemaVersion,
			},
			validMsg("ord-ok-1", 3),
			validMsg("ord-bad-row-2", 4),
		}

		inserter := &fakeBQInserter{
			putFunc: func(call int, rows []bigquery.ValueSaver) error {
				return bigquery.PutMultiError{
					{RowIndex: 1, Errors: []error{errors.New("row 1 rejected")}},
				}
			},
		}
		dlq := &fakeDLQWriter{}

		err := flushBatchWithDeps(items, inserter, dlq, "cdc.dlq", cfg, Metrics{}, nil, nil)
		require.NoError(t, err)

		require.Len(t, inserter.batches, 1)
		assert.Len(t, inserter.batches[0], 2)

		require.Len(t, dlq.messages, 3)
		h0 := headersToMap(dlq.messages[0].Headers)
		h1 := headersToMap(dlq.messages[1].Headers)
		h2 := headersToMap(dlq.messages[2].Headers)
		assert.Equal(t, "partition_key_missing", h0["error_category"])
		assert.Equal(t, "schema_version_invalid", h1["error_category"])
		assert.Equal(t, "processing_error", h2["error_category"])
		assert.Equal(t, "ord-bad-row-2", string(dlq.messages[2].Key))
	})

	t.Run("routes all valid items to DLQ when BigQuery insert fails permanently", func(t *testing.T) {
		items := []batchItem{
			validMsg("ord-10", 10),
			validMsg("ord-11", 11),
		}
		inserter := &fakeBQInserter{
			putFunc: func(call int, rows []bigquery.ValueSaver) error {
				return &googleapi.Error{Code: http.StatusBadRequest, Message: "table missing"}
			},
		}
		dlq := &fakeDLQWriter{}

		err := flushBatchWithDeps(items, inserter, dlq, "cdc.dlq", cfg, Metrics{}, nil, nil)
		require.NoError(t, err)
		require.Len(t, dlq.messages, 2)
	})
}

func TestRunBatcherWithDeps_FlushesOnBatchSizeAndClose(t *testing.T) {
	cfg := Config{
		BatchSize:          2,
		BatchTimeout:       20 * time.Millisecond,
		BQInsertTimeout:    time.Second,
		DLQWriteTimeout:    time.Second,
		KafkaCommitTimeout: time.Second,
	}

	batchCh := make(chan batchItem, 4)
	inserter := &fakeBQInserter{}
	dlq := &fakeDLQWriter{}

	makeItem := func(key string, offset int64) batchItem {
		msg := kafka.Message{
			Topic:     "orders.cdc",
			Partition: 0,
			Offset:    offset,
			Key:       []byte(key),
			Value:     []byte(`{"op":"c","after":{"id":"` + key + `"}}`),
		}
		saver, _ := transformMessage(msg)
		return batchItem{message: msg, row: saver}
	}

	batchCh <- makeItem("ord-1", 1)
	batchCh <- makeItem("ord-2", 2)
	batchCh <- makeItem("ord-3", 3)
	close(batchCh)

	err := runBatcherWithDeps(batchCh, inserter, dlq, "cdc.dlq", cfg, Metrics{}, nil, nil)
	require.NoError(t, err)
	require.Len(t, inserter.batches, 2)
	assert.Len(t, inserter.batches[0], 2)
	assert.Len(t, inserter.batches[1], 1)
}
