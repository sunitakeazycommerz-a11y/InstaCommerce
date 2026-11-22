package main

import (
	"bufio"
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
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

	reqUnauth := httptest.NewRequest(http.MethodGet, "/api/v1/subscriptions", nil)
	recUnauth := httptest.NewRecorder()
	router.ServeHTTP(recUnauth, reqUnauth)
	assert.Equal(t, http.StatusUnauthorized, recUnauth.Code)

	reqAuth := httptest.NewRequest(http.MethodGet, "/api/v1/subscriptions", nil)
	reqAuth.Header.Set("X-Internal-Service", "admin-gateway")
	reqAuth.Header.Set("X-Internal-Token", "test-rev-etl-token")
	recAuth := httptest.NewRecorder()
	router.ServeHTTP(recAuth, reqAuth)
	assert.Equal(t, http.StatusOK, recAuth.Code)

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
	res, err := pool.SyncUserFeatures(context.Background(), "job-1", "sub-1", []subscription.UserFeatureRecord{{UserID: "u-1"}})
	require.Error(t, err)
	assert.Contains(t, err.Error(), "redis client not configured")
	assert.Equal(t, "FAILED", res.Status)
}

func readRESPCommand(r *bufio.Reader) ([]string, error) {
	line, err := r.ReadString('\n')
	if err != nil {
		return nil, err
	}
	line = strings.TrimSuffix(line, "\r\n")
	if !strings.HasPrefix(line, "*") {
		return strings.Fields(line), nil
	}
	count, err := strconv.Atoi(strings.TrimPrefix(line, "*"))
	if err != nil {
		return nil, err
	}
	args := make([]string, 0, count)
	for i := 0; i < count; i++ {
		lenLine, err := r.ReadString('\n')
		if err != nil {
			return nil, err
		}
		n, err := strconv.Atoi(strings.TrimPrefix(strings.TrimSuffix(lenLine, "\r\n"), "$"))
		if err != nil {
			return nil, err
		}
		buf := make([]byte, n+2)
		if _, err := io.ReadFull(r, buf); err != nil {
			return nil, err
		}
		args = append(args, string(buf[:n]))
	}
	return args, nil
}

func startFakeRedisPingServer(t *testing.T) string {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	require.NoError(t, err)
	t.Cleanup(func() { _ = ln.Close() })

	go func() {
		for {
			conn, acceptErr := ln.Accept()
			if acceptErr != nil {
				return
			}
			go func(c net.Conn) {
				defer c.Close()
				reader := bufio.NewReader(c)
				for {
					args, readErr := readRESPCommand(reader)
					if readErr != nil {
						return
					}
					if len(args) == 0 {
						continue
					}
					var reply string
					switch strings.ToUpper(args[0]) {
					case "HELLO":
						reply = "-ERR unknown command 'HELLO'\r\n"
					case "PING":
						reply = "+PONG\r\n"
					default:
						reply = "+OK\r\n"
					}
					if _, writeErr := c.Write([]byte(reply)); writeErr != nil {
						return
					}
				}
			}(conn)
		}
	}()
	return ln.Addr().String()
}

func TestBuildHealthChecker_HonestDependencyReporting(t *testing.T) {
	t.Run("unconfigured REDIS_ADDR reports UNCONFIGURED and 200 readiness", func(t *testing.T) {
		t.Setenv("REDIS_ADDR", "")
		cfg, err := config.Load()
		require.NoError(t, err)
		assert.Equal(t, "", cfg.RedisAddr)

		rdb := newRedisClient(cfg)
		assert.Nil(t, rdb)

		checker := buildHealthChecker(cfg, nil, rdb)
		recReady := httptest.NewRecorder()
		checker.Readiness(recReady, httptest.NewRequest(http.MethodGet, "/health/ready", nil))
		assert.Equal(t, http.StatusOK, recReady.Code)

		var readyResp health.HealthResponse
		require.NoError(t, json.NewDecoder(recReady.Body).Decode(&readyResp))
		assert.Equal(t, health.StatusUP, readyResp.Status)
		assert.Equal(t, health.StatusUNCONFIGURED, readyResp.Checks["database"])
		assert.Equal(t, health.StatusUNCONFIGURED, readyResp.Checks["redis"])
		assert.Equal(t, health.StatusUNCONFIGURED, readyResp.Checks["kafka"])
		assert.Equal(t, health.StatusUNCONFIGURED, readyResp.Checks["temporal"])

		unusedClient := redis.NewClient(&redis.Options{Addr: "127.0.0.1:1"})
		defer unusedClient.Close()
		wsChecker := buildHealthChecker(&config.Config{Version: "9.9.9", RedisAddr: "   "}, nil, unusedClient)
		wsRec := httptest.NewRecorder()
		wsChecker.Readiness(wsRec, httptest.NewRequest(http.MethodGet, "/health/ready", nil))
		assert.Equal(t, http.StatusOK, wsRec.Code)
		var wsResp health.HealthResponse
		require.NoError(t, json.NewDecoder(wsRec.Body).Decode(&wsResp))
		assert.Equal(t, health.StatusUNCONFIGURED, wsResp.Checks["redis"])
	})

	t.Run("configured Redis UP reports UP and 200 readiness", func(t *testing.T) {
		addr := startFakeRedisPingServer(t)
		cfg := &config.Config{Version: "9.9.9", RedisAddr: addr}
		rdb := newRedisClient(cfg)
		require.NotNil(t, rdb)
		defer rdb.Close()

		checker := buildHealthChecker(cfg, nil, rdb)
		recReady := httptest.NewRecorder()
		checker.Readiness(recReady, httptest.NewRequest(http.MethodGet, "/health/ready", nil))
		assert.Equal(t, http.StatusOK, recReady.Code)

		var readyResp health.HealthResponse
		require.NoError(t, json.NewDecoder(recReady.Body).Decode(&readyResp))
		assert.Equal(t, health.StatusUP, readyResp.Status)
		assert.Equal(t, health.StatusUP, readyResp.Checks["redis"])
	})

	t.Run("configured Redis DOWN reports DOWN and 503 readiness", func(t *testing.T) {
		ln, err := net.Listen("tcp", "127.0.0.1:0")
		require.NoError(t, err)
		closedAddr := ln.Addr().String()
		require.NoError(t, ln.Close())

		cfg := &config.Config{Version: "9.9.9", RedisAddr: closedAddr}
		rdb := newRedisClient(cfg)
		require.NotNil(t, rdb)
		defer rdb.Close()

		checker := buildHealthChecker(cfg, nil, rdb)

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
	})
}
