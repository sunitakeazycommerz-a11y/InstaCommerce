package config

import (
	"os"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestConfigDefaults(t *testing.T) {
	// Clean environment variables before test
	os.Unsetenv("API_ADDR")
	os.Unsetenv("HEALTH_ADDR")
	os.Unsetenv("METRICS_ADDR")
	os.Unsetenv("REDIS_ADDR")
	os.Unsetenv("WORKER_POOL_CONCURRENCY")
	os.Unsetenv("SYNC_RATE_LIMIT_PER_SEC")

	cfg, err := Load()
	require.NoError(t, err)
	require.NotNil(t, cfg)

	assert.Equal(t, ":8109", cfg.APIAddr)
	assert.Equal(t, ":8110", cfg.HealthAddr)
	assert.Equal(t, ":9090", cfg.MetricsAddr)
	assert.Equal(t, "localhost:6379", cfg.RedisAddr)
	assert.Equal(t, 10, cfg.WorkerPoolConcurrency)
	assert.Equal(t, 1000, cfg.RateLimitPerSec)
	assert.Equal(t, "reverse-etl-orchestrator", cfg.ServiceName)
}

func TestConfigCustomEnvironment(t *testing.T) {
	t.Setenv("API_ADDR", ":8888")
	t.Setenv("REDIS_ADDR", "redis-cluster.prod:6379")
	t.Setenv("WORKER_POOL_CONCURRENCY", "20")
	t.Setenv("SYNC_RATE_LIMIT_PER_SEC", "2500")

	cfg, err := Load()
	require.NoError(t, err)
	require.NotNil(t, cfg)

	assert.Equal(t, ":8888", cfg.APIAddr)
	assert.Equal(t, "redis-cluster.prod:6379", cfg.RedisAddr)
	assert.Equal(t, 20, cfg.WorkerPoolConcurrency)
	assert.Equal(t, 2500, cfg.RateLimitPerSec)
}
