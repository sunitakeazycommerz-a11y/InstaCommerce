package main

import (
	"context"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/stretchr/testify/assert"

	"github.com/instacommerce/reverse-etl-orchestrator/internal/api"
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
