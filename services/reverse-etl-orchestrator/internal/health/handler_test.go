package health

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

type mockPinger struct {
	err   error
	delay time.Duration
}

func (m *mockPinger) Ping(ctx context.Context) error {
	if m.delay > 0 {
		select {
		case <-time.After(m.delay):
		case <-ctx.Done():
			return ctx.Err()
		}
	}
	return m.err
}

func TestHealthHandlerHealthy(t *testing.T) {
	checker := NewChecker(&mockPinger{err: nil})

	req := httptest.NewRequest(http.MethodGet, "/health", nil)
	rec := httptest.NewRecorder()

	checker.Health(rec, req)

	assert.Equal(t, http.StatusOK, rec.Code)
	var resp HealthResponse
	err := json.NewDecoder(rec.Body).Decode(&resp)
	require.NoError(t, err)
	assert.Equal(t, StatusUP, resp.Status)
	assert.Equal(t, "1.0.0", resp.Version)
	assert.Equal(t, StatusUP, resp.Checks["database"])
	assert.Equal(t, StatusUNCONFIGURED, resp.Checks["kafka"])
	assert.Equal(t, StatusUNCONFIGURED, resp.Checks["temporal"])
}

func TestHealthHandlerAllDependenciesConfiguredAndHealthy(t *testing.T) {
	checker := NewChecker(
		&mockPinger{err: nil},
		WithKafka(&mockPinger{err: nil}),
		WithTemporal(&mockPinger{err: nil}),
		WithRedis(&mockPinger{err: nil}),
		WithVersion("2.1.0"),
	)

	for _, path := range []string{"/health", "/health/ready"} {
		t.Run(path, func(t *testing.T) {
			req := httptest.NewRequest(http.MethodGet, path, nil)
			rec := httptest.NewRecorder()
			if path == "/health" {
				checker.Health(rec, req)
			} else {
				checker.Readiness(rec, req)
			}

			assert.Equal(t, http.StatusOK, rec.Code)
			var resp HealthResponse
			require.NoError(t, json.NewDecoder(rec.Body).Decode(&resp))
			assert.Equal(t, StatusUP, resp.Status)
			assert.Equal(t, StatusUP, resp.Checks["database"])
			assert.Equal(t, StatusUP, resp.Checks["kafka"])
			assert.Equal(t, StatusUP, resp.Checks["temporal"])
			assert.Equal(t, StatusUP, resp.Checks["redis"])
			if path == "/health" {
				assert.Equal(t, "2.1.0", resp.Version)
			}
		})
	}
}

func TestHealthHandlerUnhealthy(t *testing.T) {
	checker := NewChecker(&mockPinger{err: errors.New("db connection failure")})

	req := httptest.NewRequest(http.MethodGet, "/health", nil)
	rec := httptest.NewRecorder()

	checker.Health(rec, req)

	assert.Equal(t, http.StatusServiceUnavailable, rec.Code)
	var resp HealthResponse
	err := json.NewDecoder(rec.Body).Decode(&resp)
	require.NoError(t, err)
	assert.Equal(t, StatusDOWN, resp.Status)
	assert.Equal(t, StatusDOWN, resp.Checks["database"])
	assert.Equal(t, StatusUNCONFIGURED, resp.Checks["kafka"])
	assert.Equal(t, StatusUNCONFIGURED, resp.Checks["temporal"])
}

func TestReadinessAndHealthFailWhenOptionalConfiguredDependencyFails(t *testing.T) {
	cases := []struct {
		name      string
		opts      []Option
		wantDown  string
		wantOther map[string]string
	}{
		{
			name: "kafka down",
			opts: []Option{
				WithKafka(&mockPinger{err: errors.New("kafka unreachable")}),
			},
			wantDown: "kafka",
			wantOther: map[string]string{
				"database": StatusUP,
				"temporal": StatusUNCONFIGURED,
			},
		},
		{
			name: "temporal down",
			opts: []Option{
				WithKafka(&mockPinger{err: nil}),
				WithTemporal(&mockPinger{err: errors.New("temporal unavailable")}),
			},
			wantDown: "temporal",
			wantOther: map[string]string{
				"database": StatusUP,
				"kafka":    StatusUP,
			},
		},
		{
			name: "redis down",
			opts: []Option{
				WithRedis(&mockPinger{err: errors.New("redis timeout")}),
			},
			wantDown: "redis",
			wantOther: map[string]string{
				"database": StatusUP,
				"kafka":    StatusUNCONFIGURED,
				"temporal": StatusUNCONFIGURED,
			},
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			checker := NewChecker(&mockPinger{err: nil}, tc.opts...)
			for _, ep := range []struct {
				path    string
				handler http.HandlerFunc
			}{
				{path: "/health", handler: checker.Health},
				{path: "/health/ready", handler: checker.Readiness},
			} {
				req := httptest.NewRequest(http.MethodGet, ep.path, nil)
				rec := httptest.NewRecorder()
				ep.handler(rec, req)

				assert.Equal(t, http.StatusServiceUnavailable, rec.Code)
				var resp HealthResponse
				require.NoError(t, json.NewDecoder(rec.Body).Decode(&resp))
				assert.Equal(t, StatusDOWN, resp.Status)
				assert.Equal(t, StatusDOWN, resp.Checks[tc.wantDown])
				for k, v := range tc.wantOther {
					assert.Equal(t, v, resp.Checks[k])
				}
			}
		})
	}
}

func TestLivenessHandlerRemainsUpEvenWhenDependenciesDown(t *testing.T) {
	checker := NewChecker(
		&mockPinger{err: errors.New("db down")},
		WithKafka(&mockPinger{err: errors.New("kafka down")}),
		WithTemporal(&mockPinger{err: errors.New("temporal down")}),
		WithRedis(&mockPinger{err: errors.New("redis down")}),
	)

	req := httptest.NewRequest(http.MethodGet, "/health/live", nil)
	rec := httptest.NewRecorder()

	checker.Liveness(rec, req)

	assert.Equal(t, http.StatusOK, rec.Code)
	var resp HealthResponse
	err := json.NewDecoder(rec.Body).Decode(&resp)
	require.NoError(t, err)
	assert.Equal(t, StatusUP, resp.Status)
	assert.Empty(t, resp.Checks)
}

func TestReadinessHandler(t *testing.T) {
	checker := NewChecker(&mockPinger{err: nil})

	req := httptest.NewRequest(http.MethodGet, "/health/ready", nil)
	rec := httptest.NewRecorder()

	checker.Readiness(rec, req)

	assert.Equal(t, http.StatusOK, rec.Code)
	var resp HealthResponse
	err := json.NewDecoder(rec.Body).Decode(&resp)
	require.NoError(t, err)
	assert.Equal(t, StatusUP, resp.Status)
	assert.Equal(t, StatusUP, resp.Checks["database"])
	assert.Equal(t, StatusUNCONFIGURED, resp.Checks["kafka"])
	assert.Equal(t, StatusUNCONFIGURED, resp.Checks["temporal"])
}

func TestCheckerHandlesTypedNilAndTimeout(t *testing.T) {
	var typedNil *mockPinger
	checker := NewChecker(typedNil, WithKafka(typedNil), WithTemporal(nil))

	req := httptest.NewRequest(http.MethodGet, "/health/ready", nil)
	rec := httptest.NewRecorder()
	checker.Readiness(rec, req)

	assert.Equal(t, http.StatusOK, rec.Code)
	var resp HealthResponse
	require.NoError(t, json.NewDecoder(rec.Body).Decode(&resp))
	assert.Equal(t, StatusUP, resp.Status)
	assert.Equal(t, StatusUNCONFIGURED, resp.Checks["database"])
	assert.Equal(t, StatusUNCONFIGURED, resp.Checks["kafka"])
	assert.Equal(t, StatusUNCONFIGURED, resp.Checks["temporal"])

	slowChecker := NewChecker(
		&mockPinger{delay: 200 * time.Millisecond},
		WithCheckTimeout(20*time.Millisecond),
	)
	recTimeout := httptest.NewRecorder()
	slowChecker.Readiness(recTimeout, httptest.NewRequest(http.MethodGet, "/health/ready", nil))
	assert.Equal(t, http.StatusServiceUnavailable, recTimeout.Code)
	var timeoutResp HealthResponse
	require.NoError(t, json.NewDecoder(recTimeout.Body).Decode(&timeoutResp))
	assert.Equal(t, StatusDOWN, timeoutResp.Status)
	assert.Equal(t, StatusDOWN, timeoutResp.Checks["database"])
}
