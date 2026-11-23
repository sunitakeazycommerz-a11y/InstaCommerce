package main

import (
	"bytes"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/instacommerce/dispatch-optimizer-service/handler"
	"github.com/instacommerce/dispatch-optimizer-service/optimizer"
	"github.com/instacommerce/go-shared/pkg/auth"
	"github.com/instacommerce/go-shared/pkg/health"
	"github.com/stretchr/testify/assert"
)

func TestDispatchOptimizerMux_EnforcesInternalAuthOnBusinessRoutesOnly(t *testing.T) {
	t.Setenv("INTERNAL_SERVICE_TOKEN", "dispatch-secret-token")
	t.Setenv("INTERNAL_SERVICE_ALLOWED_CALLERS", "")
	t.Setenv("INTERNAL_SERVICE_STRICT_PER_SERVICE", "false")

	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	hHandler := health.NewHandler()
	hHandler.SetReady(true)
	authMw := auth.NewInternalAuthMiddleware(logger)
	solver := optimizer.NewSolver(optimizer.SolverConfig{})
	matcher := optimizer.NewBipartiteMatcher(nil, solver)

	mux := newHTTPMux(
		hHandler,
		authMw,
		solver,
		matcher,
		&handler.NoopEventPublisher{Logger: logger},
		handler.NewInMemoryRiderMutex(),
		logger,
		nil,
	)

	// Health and metrics remain public
	for _, path := range []string{"/health", "/health/ready", "/health/live", "/metrics"} {
		req := httptest.NewRequest(http.MethodGet, path, nil)
		rec := httptest.NewRecorder()
		mux.ServeHTTP(rec, req)
		assert.Equal(t, http.StatusOK, rec.Code, "path=%s should be public", path)
	}

	// Business endpoints reject missing or invalid internal auth
	validV2Body := `{"riders":[{"id":"r1","position":{"lat":12.97,"lng":77.59},"is_available":true,"avg_rating":4.9}],"orders":[{"id":"o1","position":{"lat":12.971,"lng":77.591}}]}`
	businessRoutes := []string{"/optimize/assign", "/v2/optimize/assign", "/dispatch/assign", "/dispatch/response"}
	for _, path := range businessRoutes {
		unauthReq := httptest.NewRequest(http.MethodPost, path, bytes.NewBufferString(validV2Body))
		unauthRec := httptest.NewRecorder()
		mux.ServeHTTP(unauthRec, unauthReq)
		assert.Equal(t, http.StatusUnauthorized, unauthRec.Code, "path=%s should require internal auth", path)

		badTokReq := httptest.NewRequest(http.MethodPost, path, bytes.NewBufferString(validV2Body))
		badTokReq.Header.Set(auth.HeaderService, "rider-fleet-service")
		badTokReq.Header.Set(auth.HeaderToken, "wrong-token")
		badTokRec := httptest.NewRecorder()
		mux.ServeHTTP(badTokRec, badTokReq)
		assert.Equal(t, http.StatusForbidden, badTokRec.Code, "path=%s should forbid bad token", path)
	}

	authedReq := httptest.NewRequest(http.MethodPost, "/v2/optimize/assign", bytes.NewBufferString(validV2Body))
	authedReq.Header.Set(auth.HeaderService, "rider-fleet-service")
	authedReq.Header.Set(auth.HeaderToken, "dispatch-secret-token")
	authedRec := httptest.NewRecorder()
	mux.ServeHTTP(authedRec, authedReq)
	assert.Equal(t, http.StatusOK, authedRec.Code, "body=%s", authedRec.Body.String())
}
