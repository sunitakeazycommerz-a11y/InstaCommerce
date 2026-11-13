package subscription

import (
	"context"
	"database/sql"
	"database/sql/driver"
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
	mu        sync.RWMutex
	hashes    map[string]map[string]any
	values    map[string]string
	ttls      map[string]time.Duration
	expireErr error
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
	if m.expireErr != nil {
		cmd.SetErr(m.expireErr)
		return cmd
	}
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

type recordedAuditExec struct {
	query       string
	args        []driver.NamedValue
	ctxErr      error
	hasDeadline bool
}

type auditCaptureDB struct {
	mu    sync.Mutex
	execs []recordedAuditExec
}

func (a *auditCaptureDB) Connect(_ context.Context) (driver.Conn, error) {
	return a, nil
}

func (a *auditCaptureDB) Driver() driver.Driver {
	return a
}

func (a *auditCaptureDB) Open(_ string) (driver.Conn, error) {
	return a, nil
}

func (a *auditCaptureDB) Prepare(_ string) (driver.Stmt, error) {
	return nil, driver.ErrSkip
}

func (a *auditCaptureDB) Close() error {
	return nil
}

func (a *auditCaptureDB) Begin() (driver.Tx, error) {
	return nil, driver.ErrSkip
}

func (a *auditCaptureDB) ExecContext(ctx context.Context, query string, args []driver.NamedValue) (driver.Result, error) {
	_, hasDeadline := ctx.Deadline()
	a.mu.Lock()
	a.execs = append(a.execs, recordedAuditExec{
		query:       query,
		args:        args,
		ctxErr:      ctx.Err(),
		hasDeadline: hasDeadline,
	})
	a.mu.Unlock()
	return driver.RowsAffected(1), nil
}

func TestSyncUserFeaturesApplies36HourTTL(t *testing.T) {
	client := newMockRedisClient()
	pool := NewWorkerPool(WorkerPoolConfig{
		Concurrency:     5,
		RateLimitPerSec: 5000,
		RedisClient:     client,
	})
	defer pool.Close()

	records := []UserFeatureRecord{
		{
			UserID:           "user-456",
			CLV:              12500.50,
			CategoryAffinity: map[string]float64{"dairy": 0.85},
			LastPurchaseDays: 2,
			Segment:          "HIGH_VALUE",
			UpdatedAt:        time.Now(),
		},
	}

	result, err := pool.SyncUserFeatures(context.Background(), "job-ttl-1", "sub-1", records)
	require.NoError(t, err)
	assert.Equal(t, "COMPLETED", result.Status)

	client.mu.RLock()
	defer client.mu.RUnlock()
	ttl, hasTTL := client.ttls["user:features:user-456"]
	require.True(t, hasTTL, "user:features:{id} must have an explicit Redis TTL")
	assert.Equal(t, 36*time.Hour, ttl)
}

func TestRecordAuditPersistsCancelledRowWithCanceledContext(t *testing.T) {
	capture := &auditCaptureDB{}
	db := sql.OpenDB(capture)
	defer db.Close()

	client := newMockRedisClient()
	pool := NewWorkerPool(WorkerPoolConfig{
		Concurrency:     5,
		RateLimitPerSec: 5000,
		RedisClient:     client,
		DB:              db,
	})
	defer pool.Close()

	canceledCtx, cancel := context.WithCancel(context.Background())
	cancel()

	records := []UserFeatureRecord{
		{UserID: "user-1", CLV: 100.0, UpdatedAt: time.Now()},
	}

	res, err := pool.SyncUserFeatures(canceledCtx, "job-cancel-1", "sub-cancel-1", records)
	require.ErrorIs(t, err, context.Canceled)
	assert.Equal(t, "CANCELLED", res.Status)

	capture.mu.Lock()
	defer capture.mu.Unlock()
	require.Len(t, capture.execs, 1, "CANCELLED audit row must still be executed against DB when sync context is canceled")
	exec := capture.execs[0]
	assert.NoError(t, exec.ctxErr, "audit DB execution must use a detached non-canceled context")
	assert.True(t, exec.hasDeadline, "audit DB execution must apply a bounded timeout")
	assert.Contains(t, exec.query, "INSERT INTO reverse_etl_sync_audit")
	require.GreaterOrEqual(t, len(exec.args), 5)
	assert.Equal(t, "job-cancel-1", exec.args[0].Value)
	assert.Equal(t, "sub-cancel-1", exec.args[1].Value)
	assert.Equal(t, "user_features", exec.args[2].Value)
	assert.Equal(t, "CANCELLED", exec.args[4].Value)
}

func TestSyncUserFeaturesFailsWhenExpireErrors(t *testing.T) {
	client := newMockRedisClient()
	client.expireErr = fmt.Errorf("redis expire unavailable")

	pool := NewWorkerPool(WorkerPoolConfig{
		Concurrency:     2,
		RateLimitPerSec: 5000,
		RedisClient:     client,
	})
	defer pool.Close()

	records := []UserFeatureRecord{
		{UserID: "user-err", CLV: 50.0, UpdatedAt: time.Now()},
	}

	res, err := pool.SyncUserFeatures(context.Background(), "job-expire-err", "sub-1", records)
	require.Error(t, err)
	assert.Equal(t, "FAILED", res.Status)
	assert.Contains(t, err.Error(), "set TTL on user features")
}
