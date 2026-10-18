package subscription

import (
	"context"
	"fmt"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/redis/go-redis/v9"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// mockRedisClient implements RedisClient for unit testing in-memory.
type mockRedisClient struct {
	mu     sync.RWMutex
	hashes map[string]map[string]any
	values map[string]string
	ttls   map[string]time.Duration
}

func newMockRedisClient() *mockRedisClient {
	return &mockRedisClient{
		hashes: make(map[string]map[string]any),
		values: make(map[string]string),
		ttls:   make(map[string]time.Duration),
	}
}

func (m *mockRedisClient) HSet(ctx context.Context, key string, values ...any) *redis.IntCmd {
	m.mu.Lock()
	defer m.mu.Unlock()

	if _, ok := m.hashes[key]; !ok {
		m.hashes[key] = make(map[string]any)
	}

	var count int64
	for i := 0; i < len(values); i += 2 {
		if i+1 < len(values) {
			field := fmt.Sprintf("%v", values[i])
			m.hashes[key][field] = values[i+1]
			count++
		}
	}
	cmd := redis.NewIntCmd(ctx)
	cmd.SetVal(count)
	return cmd
}

func (m *mockRedisClient) Rename(ctx context.Context, key, newKey string) *redis.StatusCmd {
	m.mu.Lock()
	defer m.mu.Unlock()

	cmd := redis.NewStatusCmd(ctx)
	if hash, ok := m.hashes[key]; ok {
		m.hashes[newKey] = hash
		delete(m.hashes, key)
		cmd.SetVal("OK")
		return cmd
	}

	if val, ok := m.values[key]; ok {
		m.values[newKey] = val
		delete(m.values, key)
		if ttl, hasTTL := m.ttls[key]; hasTTL {
			m.ttls[newKey] = ttl
			delete(m.ttls, key)
		}
		cmd.SetVal("OK")
		return cmd
	}

	cmd.SetErr(redis.Nil)
	return cmd
}

func (m *mockRedisClient) Set(ctx context.Context, key string, value any, expiration time.Duration) *redis.StatusCmd {
	m.mu.Lock()
	defer m.mu.Unlock()

	m.values[key] = fmt.Sprintf("%v", value)
	if expiration > 0 {
		m.ttls[key] = expiration
	}
	cmd := redis.NewStatusCmd(ctx)
	cmd.SetVal("OK")
	return cmd
}

func (m *mockRedisClient) Get(ctx context.Context, key string) *redis.StringCmd {
	m.mu.RLock()
	defer m.mu.RUnlock()

	cmd := redis.NewStringCmd(ctx)
	if val, ok := m.values[key]; ok {
		cmd.SetVal(val)
	} else {
		cmd.SetErr(redis.Nil)
	}
	return cmd
}

func (m *mockRedisClient) Expire(ctx context.Context, key string, expiration time.Duration) *redis.BoolCmd {
	m.mu.Lock()
	defer m.mu.Unlock()

	cmd := redis.NewBoolCmd(ctx)
	if _, ok := m.values[key]; ok {
		m.ttls[key] = expiration
		cmd.SetVal(true)
		return cmd
	}
	if _, ok := m.hashes[key]; ok {
		m.ttls[key] = expiration
		cmd.SetVal(true)
		return cmd
	}
	cmd.SetVal(false)
	return cmd
}

func (m *mockRedisClient) Del(ctx context.Context, keys ...string) *redis.IntCmd {
	m.mu.Lock()
	defer m.mu.Unlock()

	var deleted int64
	for _, k := range keys {
		if _, ok := m.values[k]; ok {
			delete(m.values, k)
			deleted++
		}
		if _, ok := m.hashes[k]; ok {
			delete(m.hashes, k)
			deleted++
		}
	}
	cmd := redis.NewIntCmd(ctx)
	cmd.SetVal(deleted)
	return cmd
}

func (m *mockRedisClient) Ping(ctx context.Context) *redis.StatusCmd {
	cmd := redis.NewStatusCmd(ctx)
	cmd.SetVal("PONG")
	return cmd
}

func TestSyncUserFeaturesIdempotencyAtomicRename(t *testing.T) {
	// Test #649: Synced features written to temporary key then atomically renamed to user:features:{userId}
	client := newMockRedisClient()
	pool := NewWorkerPool(WorkerPoolConfig{
		Concurrency:     5,
		RateLimitPerSec: 5000,
		RedisClient:     client,
	})
	defer pool.Close()

	ctx := context.Background()
	records := []UserFeatureRecord{
		{
			UserID:           "user-456",
			CLV:              12500.50,
			CategoryAffinity: map[string]float64{"dairy": 0.85, "produce": 0.92},
			LastPurchaseDays: 2,
			Segment:          "HIGH_VALUE",
			UpdatedAt:        time.Now(),
		},
	}

	result, err := pool.SyncUserFeatures(ctx, "job-sync-100", "sub-1", records)
	require.NoError(t, err)
	assert.Equal(t, int64(1), result.RowsProcessed)
	assert.Equal(t, "COMPLETED", result.Status)

	// Verify temporary key was cleaned up and production key contains all feature fields
	client.mu.RLock()
	defer client.mu.RUnlock()

	assert.NotContains(t, client.hashes, "user:features:temp:job-sync-100:user-456")
	prodHash, exists := client.hashes["user:features:user-456"]
	require.True(t, exists)
	assert.Equal(t, "12500.50", prodHash["clv"])
	assert.Equal(t, "HIGH_VALUE", prodHash["segment"])
	assert.Equal(t, 2, prodHash["last_purchase_days"])
	assert.Contains(t, prodHash["category_affinity"], "dairy")
}

func TestSyncCustomerFraudScores36HourTTL(t *testing.T) {
	// Test #659: Customer fraud risk scores synced with 36-hour TTL and atomic rename
	client := newMockRedisClient()
	pool := NewWorkerPool(WorkerPoolConfig{
		Concurrency:     5,
		RateLimitPerSec: 5000,
		RedisClient:     client,
	})
	defer pool.Close()

	ctx := context.Background()
	scores := []CustomerFraudScoreRecord{
		{
			UserID:         "user-suspicious-99",
			FraudRiskScore: 0.94,
			ModelVersion:   "v2.1-prod",
			RiskFactors:    []string{"VELOCITY_SPIKE", "IP_GEO_MISMATCH"},
			ScoredAt:       time.Now(),
		},
	}

	result, err := pool.SyncCustomerFraudScores(ctx, "job-fraud-101", "sub-fraud-1", scores)
	require.NoError(t, err)
	assert.Equal(t, int64(1), result.RowsProcessed)
	assert.Equal(t, "COMPLETED", result.Status)

	client.mu.RLock()
	defer client.mu.RUnlock()

	// Verify temp key is removed, production key fraud:score:user-suspicious-99 exists
	assert.NotContains(t, client.values, "fraud:score:temp:job-fraud-101:user-suspicious-99")
	val, exists := client.values["fraud:score:user-suspicious-99"]
	require.True(t, exists)
	assert.Contains(t, val, "0.94")
	assert.Contains(t, val, "VELOCITY_SPIKE")

	// Verify 36-hour TTL
	ttl, hasTTL := client.ttls["fraud:score:user-suspicious-99"]
	require.True(t, hasTTL)
	assert.Equal(t, 36*time.Hour, ttl)
}

func TestSyncDarkStoreInventoryParallelConcurrency(t *testing.T) {
	// Test #670: 50 dark stores synced concurrently with 10 worker goroutines without starvation
	client := newMockRedisClient()
	pool := NewWorkerPool(WorkerPoolConfig{
		Concurrency:     10,
		RateLimitPerSec: 10000,
		RedisClient:     client,
	})
	defer pool.Close()

	ctx := context.Background()
	storeIDs := make([]string, 50)
	for i := 0; i < 50; i++ {
		storeIDs[i] = fmt.Sprintf("store-%03d", i+1)
	}

	var activeWorkers int64
	var maxObservedWorkers int64

	fetchInventory := func(ctx context.Context, storeID string) ([]InventoryRecord, error) {
		current := atomic.AddInt64(&activeWorkers, 1)
		defer atomic.AddInt64(&activeWorkers, -1)

		for {
			max := atomic.LoadInt64(&maxObservedWorkers)
			if current <= max || atomic.CompareAndSwapInt64(&maxObservedWorkers, max, current) {
				break
			}
		}

		time.Sleep(5 * time.Millisecond) // Simulate brief I/O
		return []InventoryRecord{
			{StoreID: storeID, SKU: "SKU-APPLE", Available: 100, Reserved: 5, UpdatedAt: time.Now()},
			{StoreID: storeID, SKU: "SKU-MILK", Available: 50, Reserved: 0, UpdatedAt: time.Now()},
		}, nil
	}

	result, err := pool.SyncDarkStoreInventoryParallel(ctx, "job-inv-500", "sub-inv-1", storeIDs, fetchInventory)
	require.NoError(t, err)
	assert.Equal(t, 50, result.StoresSynced)
	assert.Equal(t, int64(100), result.RowsProcessed) // 50 stores * 2 SKUs
	assert.Equal(t, "COMPLETED", result.Status)

	// Verify parallel execution (multiple concurrent workers observed)
	assert.GreaterOrEqual(t, maxObservedWorkers, int64(2), "Expected parallel store processing")
	assert.LessOrEqual(t, maxObservedWorkers, int64(10), "Workers must be bounded by concurrency limit")

	// Verify production keys exist in Redis
	client.mu.RLock()
	defer client.mu.RUnlock()
	for _, storeID := range storeIDs {
		key := fmt.Sprintf("store:inventory:%s", storeID)
		assert.Contains(t, client.hashes, key)
	}
}

func TestRateLimiter(t *testing.T) {
	// Test rate limiter allows token consumption and stops cleanly
	rl := NewRateLimiter(100)
	ctx := context.Background()

	for i := 0; i < 10; i++ {
		err := rl.Acquire(ctx)
		require.NoError(t, err)
	}
	rl.Stop()
}
