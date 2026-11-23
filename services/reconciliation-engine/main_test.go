package main

import (
	"database/sql/driver"
	"errors"
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/instacommerce/go-shared/pkg/auth"
	"github.com/instacommerce/go-shared/pkg/health"
	"github.com/instacommerce/reconciliation-engine/api"
	"github.com/instacommerce/reconciliation-engine/internal/testdb"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestNewHTTPMux_ExactRoutesEnforceAuthAndKeepHealthPublic(t *testing.T) {
	t.Setenv("INTERNAL_SERVICE_TOKEN", "recon-secret-token")
	t.Setenv("INTERNAL_SERVICE_ALLOWED_CALLERS", "")
	t.Setenv("INTERNAL_SERVICE_STRICT_PER_SERVICE", "false")

	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	hHandler := health.NewHandler()
	hHandler.SetReady(true)
	authMw := auth.NewInternalAuthMiddleware(logger)
	reconcilerHandler := api.NewReconciliationHandler(nil, logger)

	mux := newHTTPMux(hHandler, reconcilerHandler, authMw, nil, nil)

	for _, publicPath := range []string{"/health", "/health/live", "/health/ready", "/ready", "/metrics"} {
		req := httptest.NewRequest(http.MethodGet, publicPath, nil)
		rec := httptest.NewRecorder()
		mux.ServeHTTP(rec, req)
		assert.Equal(t, http.StatusOK, rec.Code, "public path=%s", publicPath)
	}

	unauthReq := httptest.NewRequest(http.MethodGet, "/reconciliation/pending", nil)
	unauthRec := httptest.NewRecorder()
	mux.ServeHTTP(unauthRec, unauthReq)
	assert.Equal(t, http.StatusUnauthorized, unauthRec.Code)

	unauthPending := httptest.NewRequest(http.MethodGet, "/pending", nil)
	unauthPendingRec := httptest.NewRecorder()
	mux.ServeHTTP(unauthPendingRec, unauthPending)
	assert.Equal(t, http.StatusUnauthorized, unauthPendingRec.Code)

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
		req.Header.Set(auth.HeaderService, "admin-gateway")
		req.Header.Set(auth.HeaderToken, "recon-secret-token")
		rec := httptest.NewRecorder()
		mux.ServeHTTP(rec, req)
		assert.Equal(t, tc.wantStatus, rec.Code, "unexpected status for %s %s", tc.method, tc.path)
	}
}

func TestReadyEndpoint_ChecksPostgresAndKafkaDependencies(t *testing.T) {
	healthyDB, _ := testdb.New(
		func(int64, string, []driver.NamedValue) (driver.Rows, error) { return &testdb.StubRows{}, nil },
		func(int64, string, []driver.NamedValue) (driver.Result, error) { return driver.RowsAffected(1), nil },
	)
	defer healthyDB.Close()

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	require.NoError(t, err)
	brokerAddr := ln.Addr().String()
	defer ln.Close()

	hHandler := health.NewHandler()
	hHandler.SetReady(true)

	muxReady := newHTTPMux(hHandler, nil, nil, healthyDB, []string{brokerAddr})
	req := httptest.NewRequest(http.MethodGet, "/health/ready", nil)
	rec := httptest.NewRecorder()
	muxReady.ServeHTTP(rec, req)
	assert.Equal(t, http.StatusOK, rec.Code, "body=%s", rec.Body.String())

	// Close broker listener -> readiness fails 503
	require.NoError(t, ln.Close())
	recDown := httptest.NewRecorder()
	muxReady.ServeHTTP(recDown, req)
	assert.Equal(t, http.StatusServiceUnavailable, recDown.Code)
	assert.Contains(t, recDown.Body.String(), "kafka unavailable")

	// Failing DB ping via closed sql.DB -> readiness fails 503 postgres unavailable
	closedDB, _ := testdb.New(
		func(int64, string, []driver.NamedValue) (driver.Rows, error) {
			return nil, errors.New("db down")
		},
		nil,
	)
	require.NoError(t, closedDB.Close())
	muxDBDown := newHTTPMux(hHandler, nil, nil, closedDB, nil)
	recDBDown := httptest.NewRecorder()
	muxDBDown.ServeHTTP(recDBDown, req)
	assert.Equal(t, http.StatusServiceUnavailable, recDBDown.Code)
	assert.Contains(t, recDBDown.Body.String(), "postgres unavailable")
}
