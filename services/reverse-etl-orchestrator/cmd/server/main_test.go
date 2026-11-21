package main

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/redis/go-redis/v9"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/instacommerce/reverse-etl-orchestrator/internal/api"
	"github.com/instacommerce/reverse-etl-orchestrator/internal/config"
	"github.com/instacommerce/reverse-etl-orchestrator/internal/health"
	"github.com/instacommerce/reverse-etl-orchestrator/internal/subscription"
)

type noopSubMgr struct{}

func (n *noopSubMgr) Create(_ context.Context, _ subscription.CreateRequest, _ string) (*subscription.Subscription, error) {
	return nil, nil
}
func (n *noopSubMgr) Get(_ context.Context, _ string) (*subscription.Subscription, error) {
	return nil, nil
}
func (n *noopSubMgr) List(_ context.Context, _, _ string, _ subscription.State) ([]*subscription.Subscription, error) {
	return nil, nil
}
func (n *noopSubMgr) Activate(_ context.Context, _ string) error { return nil }
func (n *noopSubMgr) Pause(_ context.Context, _ string) error    { return nil }
func (n *noopSubMgr) Archive(_ context.Context, _ string) error  { return nil }

func TestBuildAPIRouter_RequiresInternalAuthOnAPIv1(t *testing.T) {
	t.Setenv("INTERNAL_SERVICE_TOKEN", "test-rev-etl-token")
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	apiHandler := api.NewHandler(&noopSubMgr{})
	healthChecker := health.NewChecker(nil)

	router := buildAPIRouter(apiHandler, healthChecker, logger)

	// Unauthenticated API request -> 401
	reqUnauth := httptest.NewRequest(http.MethodGet, "/api/v1/subscriptions", nil)
	recUnauth := httptest.NewRecorder()
	router.ServeHTTP(recUnauth, reqUnauth)
	assert.Equal(t, http.StatusUnauthorized, recUnauth.Code)

	// Authenticated API request -> 200 OK
	reqAuth := httptest.NewRequest(http.MethodGet, "/api/v1/subscriptions", nil)
	reqAuth.Header.Set("X-Internal-Service", "admin-gateway")
	reqAuth.Header.Set("X-Internal-Token", "test-rev-etl-token")
	recAuth := httptest.NewRecorder()
	router.ServeHTTP(recAuth, reqAuth)
	assert.Equal(t, http.StatusOK, recAuth.Code)

	// Health endpoint stays accessible for probes
	reqHealth := httptest.NewRequest(http.MethodGet, "/health/live", nil)
	recHealth := httptest.NewRecorder()
	router.ServeHTTP(recHealth, reqHealth)
	assert.Equal(t, http.StatusOK, recHealth.Code)
}

func TestNewWorkerPoolFromConfig_UsesDefaultRetryPolicy(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	pool := newWorkerPoolFromConfig(&config.Config{
		WorkerPoolConcurrency: 4,
		RateLimitPerSec:       200,
	}, nil, nil, logger)
	defer pool.Close()

	assert.Equal(t, subscription.DefaultRetryPolicy(), pool.EffectiveRetryPolicy())
}

func TestBuildHealthChecker_HonestDependencyReporting(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	require.NoError(t, err)
	closedAddr := ln.Addr().String()
	require.NoError(t, ln.Close())

	rdb := redis.NewClient(&redis.Options{Addr: closedAddr})
	defer rdb.Close()

	checker := buildHealthChecker(&config.Config{Version: "9.9.9"}, nil, rdb)

	recReady := httptest.NewRecorder()
	checker.Readiness(recReady, httptest.NewRequest(http.MethodGet, "/health/ready", nil))
	assert.Equal(t, http.StatusServiceUnavailable, recReady.Code)

	var readyResp health.HealthResponse
	require.NoError(t, json.NewDecoder(recReady.Body).Decode(&readyResp))
	assert.Equal(t, health.StatusDOWN, readyResp.Status)
	assert.Equal(t, health.StatusUNCONFIGURED, readyResp.Checks["database"])
	assert.Equal(t, health.StatusDOWN, readyResp.Checks["redis"])
	assert.Equal(t, health.StatusUNCONFIGURED, readyResp.Checks["kafka"])
	assert.Equal(t, health.StatusUNCONFIGURED, readyResp.Checks["temporal"])

	recHealth := httptest.NewRecorder()
	checker.Health(recHealth, httptest.NewRequest(http.MethodGet, "/health", nil))
	assert.Equal(t, http.StatusServiceUnavailable, recHealth.Code)

	var healthResp health.HealthResponse
	require.NoError(t, json.NewDecoder(recHealth.Body).Decode(&healthResp))
	assert.Equal(t, "9.9.9", healthResp.Version)
	assert.Equal(t, health.StatusDOWN, healthResp.Status)

	recLive := httptest.NewRecorder()
	checker.Liveness(recLive, httptest.NewRequest(http.MethodGet, "/health/live", nil))
	assert.Equal(t, http.StatusOK, recLive.Code)
}
