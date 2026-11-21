package main

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/instacommerce/go-shared/pkg/health"
	"github.com/instacommerce/reconciliation-engine/api"
	"github.com/segmentio/kafka-go"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

type stubFileKafkaWriter struct {
	err      error
	messages []kafka.Message
}

func (s *stubFileKafkaWriter) WriteMessages(_ context.Context, msgs ...kafka.Message) error {
	if s.err != nil {
		return s.err
	}
	s.messages = append(s.messages, msgs...)
	return nil
}

func (s *stubFileKafkaWriter) Close() error { return nil }

func TestPSPSourceLoad_RequiresPath(t *testing.T) {
	src := &PSPSource{logger: slog.New(slog.NewTextHandler(io.Discard, nil))}
	txns, err := src.Load(context.Background())
	require.Error(t, err)
	assert.Nil(t, txns)
	assert.Contains(t, err.Error(), "psp export path is required")
}

func TestReconcilerRunOnce_PersistAndPublishErrorsNeverDiscardedOrCounted(t *testing.T) {
	tmpDir := t.TempDir()
	pspPath := filepath.Join(tmpDir, "psp.json")
	require.NoError(t, os.WriteFile(pspPath, []byte(`[{"id":"txn-1","amount_cents":1050,"currency":"USD","status":"captured"}]`), 0o600))
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))

	t.Run("fix registry write failure blocks Fixed increment and rolls back ledger", func(t *testing.T) {
		badFixDir := filepath.Join(tmpDir, "fix-is-a-dir")
		require.NoError(t, os.MkdirAll(badFixDir, 0o755))

		ledgerPath := filepath.Join(tmpDir, "ledger-ok.json")
		ledgerStore := &LedgerStore{
			entries: map[string]LedgerEntry{
				"txn-1": {ID: "txn-1", AmountCents: 1000, Currency: "USD", Type: "payment", UpdatedAt: time.Now().UTC()},
			},
			outputPath: ledgerPath,
			logger:     logger,
		}
		fixRegistry := &FixRegistry{
			records: make(map[string]FixRecord),
			path:    badFixDir,
			logger:  logger,
		}
		r := &Reconciler{
			logger:      logger,
			pspSource:   &PSPSource{path: pspPath, logger: logger},
			ledgerStore: ledgerStore,
			fixRegistry: fixRegistry,
			publisher:   &KafkaPublisher{enabled: false, logger: logger},
			timeout:     5 * time.Second,
		}

		counts, err := r.runOnce(context.Background())
		require.Error(t, err)
		assert.Equal(t, 1, counts.Mismatches)
		assert.Equal(t, 0, counts.Fixed)
		assert.False(t, fixRegistry.Exists("amount_mismatch:txn-1"))

		restored, ok := ledgerStore.Get("txn-1")
		require.True(t, ok)
		assert.Equal(t, int64(1000), restored.AmountCents)
	})

	t.Run("ledger persist failure blocks Fixed increment and fix recording", func(t *testing.T) {
		badLedgerDir := filepath.Join(tmpDir, "ledger-is-a-dir")
		require.NoError(t, os.MkdirAll(badLedgerDir, 0o755))

		ledgerStore := &LedgerStore{
			entries:    make(map[string]LedgerEntry),
			outputPath: badLedgerDir,
			logger:     logger,
		}
		fixRegistry := &FixRegistry{
			records: make(map[string]FixRecord),
			path:    filepath.Join(tmpDir, "fixes-never-written.json"),
			logger:  logger,
		}
		r := &Reconciler{
			logger:      logger,
			pspSource:   &PSPSource{path: pspPath, logger: logger},
			ledgerStore: ledgerStore,
			fixRegistry: fixRegistry,
			publisher:   &KafkaPublisher{enabled: false, logger: logger},
			timeout:     5 * time.Second,
		}

		counts, err := r.runOnce(context.Background())
		require.Error(t, err)
		assert.Equal(t, 1, counts.Mismatches)
		assert.Equal(t, 0, counts.Fixed)
		assert.False(t, fixRegistry.Exists("missing_ledger_entry:txn-1"))

		_, exists := ledgerStore.Get("txn-1")
		assert.False(t, exists)
	})

	t.Run("publish failure is surfaced via returned error", func(t *testing.T) {
		pubErr := errors.New("kafka unavailable")
		ledgerStore := &LedgerStore{
			entries:    make(map[string]LedgerEntry),
			outputPath: filepath.Join(tmpDir, "ledger-pub.json"),
			logger:     logger,
		}
		fixRegistry := &FixRegistry{
			records: make(map[string]FixRecord),
			path:    filepath.Join(tmpDir, "fixes-pub.json"),
			logger:  logger,
		}
		r := &Reconciler{
			logger:      logger,
			pspSource:   &PSPSource{path: pspPath, logger: logger},
			ledgerStore: ledgerStore,
			fixRegistry: fixRegistry,
			publisher:   &KafkaPublisher{enabled: true, writer: &stubFileKafkaWriter{err: pubErr}, logger: logger},
			timeout:     5 * time.Second,
		}

		counts, err := r.runOnce(context.Background())
		require.Error(t, err)
		assert.ErrorIs(t, err, pubErr)
		assert.Equal(t, 1, counts.Mismatches)
		assert.Equal(t, 1, counts.Fixed)
	})
}

func TestNewHTTPMux_ExactRoutesRejectArbitrarySegments(t *testing.T) {
	mux := newHTTPMux(health.NewHandler(), api.NewReconciliationHandler(nil, slog.New(slog.NewTextHandler(io.Discard, nil))))

	tests := []struct {
		method     string
		path       string
		wantStatus int
	}{
		{http.MethodGet, "/reconciliation/runs/invalid-id/mismatches", http.StatusBadRequest},
		{http.MethodGet, "/reconciliation/runs/42/extra/mismatches", http.StatusNotFound},
		{http.MethodGet, "/reconciliation/runs/42/other", http.StatusNotFound},
		{http.MethodPost, "/reconciliation/mismatches/invalid-id/review", http.StatusBadRequest},
		{http.MethodPost, "/reconciliation/mismatches/12/extra/review", http.StatusNotFound},
		{http.MethodPost, "/reconciliation/mismatches/invalid-id/fix", http.StatusBadRequest},
		{http.MethodPost, "/reconciliation/mismatches/12/extra/fix", http.StatusNotFound},
	}

	for _, tc := range tests {
		req := httptest.NewRequest(tc.method, tc.path, nil)
		rec := httptest.NewRecorder()
		mux.ServeHTTP(rec, req)
		assert.Equal(t, tc.wantStatus, rec.Code, "unexpected status for %s %s", tc.method, tc.path)
	}
}
