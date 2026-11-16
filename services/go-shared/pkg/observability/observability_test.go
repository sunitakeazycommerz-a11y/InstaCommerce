package observability

import (
	"context"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/prometheus/client_golang/prometheus/testutil"
)

func TestInitTracer_NoSchemaConflict(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	shutdown, err := InitTracer(context.Background(), "test-service", logger)
	if err != nil {
		t.Fatalf("InitTracer failed with error: %v", err)
	}
	if shutdown == nil {
		t.Fatal("expected non-nil shutdown function")
	}
	_ = shutdown(context.Background())
}

func TestHTTPMetrics_UnwrapAndContentLength(t *testing.T) {
	m := NewHTTPMetrics("test_obs_metrics")

	var flushed bool
	var unwrapped http.ResponseWriter
	handler := m.InstrumentHandler("/test", http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if u, ok := w.(interface{ Unwrap() http.ResponseWriter }); ok {
			unwrapped = u.Unwrap()
		}
		rc := http.NewResponseController(w)
		if err := rc.Flush(); err == nil {
			flushed = true
		}
		w.WriteHeader(http.StatusCreated)
		_, _ = w.Write([]byte("hello"))
	}))

	// Request with unknown ContentLength (-1) should not record RequestSize observation
	req := httptest.NewRequest(http.MethodGet, "/test", nil)
	req.ContentLength = -1
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)

	if unwrapped != rec {
		t.Fatalf("expected Unwrap() to return underlying ResponseRecorder, got %v", unwrapped)
	}
	if !flushed {
		t.Fatal("expected ResponseController.Flush() to succeed via Unwrap()")
	}
	if count := testutil.CollectAndCount(m.RequestSize); count != 0 {
		t.Fatalf("expected 0 RequestSize observations when ContentLength <= 0, got %d", count)
	}
}
