package auth

import (
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"testing"

	"github.com/stretchr/testify/assert"
)

func TestInternalAuthMiddleware_SkipPaths(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	m := NewInternalAuthMiddleware(logger)

	nextCalled := false
	handler := m.Wrap(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		nextCalled = true
		w.WriteHeader(http.StatusOK)
	}))

	paths := []string{"/health", "/health/ready", "/health/live", "/metrics"}
	for _, p := range paths {
		nextCalled = false
		req := httptest.NewRequest(http.MethodGet, p, nil)
		rec := httptest.NewRecorder()

		handler.ServeHTTP(rec, req)

		assert.True(t, nextCalled, "next handler should be called for skipPath %s", p)
		assert.Equal(t, http.StatusOK, rec.Code)
	}
}

func TestInternalAuthMiddleware_MissingHeaders(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	m := NewInternalAuthMiddleware(logger)

	handler := m.Wrap(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))

	// Both missing
	req := httptest.NewRequest(http.MethodGet, "/api/data", nil)
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)
	assert.Equal(t, http.StatusUnauthorized, rec.Code)

	// Missing token
	req = httptest.NewRequest(http.MethodGet, "/api/data", nil)
	req.Header.Set(HeaderService, "order-service")
	rec = httptest.NewRecorder()
	handler.ServeHTTP(rec, req)
	assert.Equal(t, http.StatusUnauthorized, rec.Code)

	// Missing service
	req = httptest.NewRequest(http.MethodGet, "/api/data", nil)
	req.Header.Set(HeaderToken, "some-token")
	rec = httptest.NewRecorder()
	handler.ServeHTTP(rec, req)
	assert.Equal(t, http.StatusUnauthorized, rec.Code)
}

func TestInternalAuthMiddleware_PerServiceToken(t *testing.T) {
	os.Setenv("INTERNAL_SERVICE_ALLOWED_CALLERS", `{"order-service":"order-secret-token","payment-service":"pay-secret-token"}`)
	os.Setenv("INTERNAL_SERVICE_TOKEN", "fallback-shared-token")
	defer func() {
		os.Unsetenv("INTERNAL_SERVICE_ALLOWED_CALLERS")
		os.Unsetenv("INTERNAL_SERVICE_TOKEN")
	}()

	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	m := NewInternalAuthMiddleware(logger)

	handler := m.Wrap(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		w.Write([]byte("ok"))
	}))

	// Valid per-service token
	req := httptest.NewRequest(http.MethodPost, "/api/resource", nil)
	req.Header.Set(HeaderService, "order-service")
	req.Header.Set(HeaderToken, "order-secret-token")
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)
	assert.Equal(t, http.StatusOK, rec.Code)
	assert.Equal(t, "ok", rec.Body.String())

	// Invalid per-service token
	req = httptest.NewRequest(http.MethodPost, "/api/resource", nil)
	req.Header.Set(HeaderService, "order-service")
	req.Header.Set(HeaderToken, "wrong-token")
	rec = httptest.NewRecorder()
	handler.ServeHTTP(rec, req)
	assert.Equal(t, http.StatusForbidden, rec.Code)
}

func TestInternalAuthMiddleware_SharedTokenFallback_AllowedWhenNotStrict(t *testing.T) {
	os.Setenv("INTERNAL_SERVICE_TOKEN", "shared-migration-token")
	os.Setenv("INTERNAL_SERVICE_STRICT_PER_SERVICE", "false")
	defer func() {
		os.Unsetenv("INTERNAL_SERVICE_TOKEN")
		os.Unsetenv("INTERNAL_SERVICE_STRICT_PER_SERVICE")
	}()

	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	m := NewInternalAuthMiddleware(logger)

	handler := m.Wrap(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))

	req := httptest.NewRequest(http.MethodGet, "/api/orders", nil)
	req.Header.Set(HeaderService, "unknown-service")
	req.Header.Set(HeaderToken, "shared-migration-token")
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)
	assert.Equal(t, http.StatusOK, rec.Code)
}

func TestInternalAuthMiddleware_SharedTokenFallback_RejectedInStrictMode(t *testing.T) {
	os.Setenv("INTERNAL_SERVICE_TOKEN", "shared-migration-token")
	os.Setenv("INTERNAL_SERVICE_STRICT_PER_SERVICE", "true")
	defer func() {
		os.Unsetenv("INTERNAL_SERVICE_TOKEN")
		os.Unsetenv("INTERNAL_SERVICE_STRICT_PER_SERVICE")
	}()

	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	m := NewInternalAuthMiddleware(logger)

	handler := m.Wrap(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))

	// Caller not in allowedCallers attempting shared token fallback in strict mode must be rejected with 403
	req := httptest.NewRequest(http.MethodGet, "/api/orders", nil)
	req.Header.Set(HeaderService, "unknown-service")
	req.Header.Set(HeaderToken, "shared-migration-token")
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)
	assert.Equal(t, http.StatusForbidden, rec.Code)
}
