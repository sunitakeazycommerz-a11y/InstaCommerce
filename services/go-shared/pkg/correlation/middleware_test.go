package correlation

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestMiddleware_GeneratesNewCorrelationIdWhenMissing(t *testing.T) {
	req := httptest.NewRequest(http.MethodGet, "/test", nil)
	rr := httptest.NewRecorder()

	var capturedCtxId string
	handler := Middleware(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		capturedCtxId = FromContext(r.Context())
		w.WriteHeader(http.StatusOK)
	}))

	handler.ServeHTTP(rr, req)

	respHeader := rr.Header().Get(Header)
	if respHeader == "" {
		t.Fatal("expected X-Correlation-ID response header, got empty")
	}
	if capturedCtxId == "" {
		t.Fatal("expected correlation ID in context, got empty")
	}
	if respHeader != capturedCtxId {
		t.Fatalf("expected header %q to match context %q", respHeader, capturedCtxId)
	}
}

func TestMiddleware_PreservesExistingCorrelationId(t *testing.T) {
	existingId := "my-trace-id-12345"
	req := httptest.NewRequest(http.MethodGet, "/test", nil)
	req.Header.Set(Header, existingId)
	rr := httptest.NewRecorder()

	var capturedCtxId string
	handler := Middleware(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		capturedCtxId = FromContext(r.Context())
		w.WriteHeader(http.StatusOK)
	}))

	handler.ServeHTTP(rr, req)

	respHeader := rr.Header().Get(Header)
	if respHeader != existingId {
		t.Fatalf("expected response header %q, got %q", existingId, respHeader)
	}
	if capturedCtxId != existingId {
		t.Fatalf("expected context ID %q, got %q", existingId, capturedCtxId)
	}
}

func TestFromContext_EmptyWhenNotSet(t *testing.T) {
	ctx := context.Background()
	if id := FromContext(ctx); id != "" {
		t.Fatalf("expected empty string from context, got %q", id)
	}
}
