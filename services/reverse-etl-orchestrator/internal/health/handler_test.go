package health

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

type mockPinger struct {
	err error
}

func (m *mockPinger) Ping(ctx context.Context) error {
	return m.err
}

func TestHealthHandlerHealthy(t *testing.T) {
	checker := NewChecker(&mockPinger{err: nil})

	req := httptest.NewRequest("GET", "/health", nil)
	rec := httptest.NewRecorder()

	checker.Health(rec, req)

	assert.Equal(t, http.StatusOK, rec.Code)
	var resp HealthResponse
	err := json.NewDecoder(rec.Body).Decode(&resp)
	require.NoError(t, err)
	assert.Equal(t, "UP", resp.Status)
	assert.Equal(t, "UP", resp.Checks["database"])
}

func TestHealthHandlerUnhealthy(t *testing.T) {
	checker := NewChecker(&mockPinger{err: errors.New("db connection failure")})

	req := httptest.NewRequest("GET", "/health", nil)
	rec := httptest.NewRecorder()

	checker.Health(rec, req)

	assert.Equal(t, http.StatusServiceUnavailable, rec.Code)
	var resp HealthResponse
	err := json.NewDecoder(rec.Body).Decode(&resp)
	require.NoError(t, err)
	assert.Equal(t, "DOWN", resp.Status)
	assert.Equal(t, "DOWN", resp.Checks["database"])
}

func TestLivenessHandler(t *testing.T) {
	checker := NewChecker(nil)

	req := httptest.NewRequest("GET", "/health/live", nil)
	rec := httptest.NewRecorder()

	checker.Liveness(rec, req)

	assert.Equal(t, http.StatusOK, rec.Code)
	var resp HealthResponse
	err := json.NewDecoder(rec.Body).Decode(&resp)
	require.NoError(t, err)
	assert.Equal(t, "UP", resp.Status)
}

func TestReadinessHandler(t *testing.T) {
	checker := NewChecker(&mockPinger{err: nil})

	req := httptest.NewRequest("GET", "/health/ready", nil)
	rec := httptest.NewRecorder()

	checker.Readiness(rec, req)

	assert.Equal(t, http.StatusOK, rec.Code)
	var resp HealthResponse
	err := json.NewDecoder(rec.Body).Decode(&resp)
	require.NoError(t, err)
	assert.Equal(t, "UP", resp.Status)
	assert.Equal(t, "UP", resp.Checks["database"])
}
