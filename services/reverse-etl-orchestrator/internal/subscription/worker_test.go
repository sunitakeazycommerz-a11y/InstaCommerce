package subscription

import (
	"context"
	"database/sql"
	"fmt"
	"sync/atomic"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestSyncUserFeaturesIdempotencyAtomicRename(t *testing.T) {
	client := newMockRedisClient()
	pool := NewWorkerPool(WorkerPoolConfig{
		Concurrency:     5,
		RateLimitPerSec: 5000,
		RedisClient:     client,
	})
	defer pool.Close()

	records := []UserFeatureRecord{
		{
			UserID:            "user-456",
			CLV:               12500.50,
			CategoryAffinity:  map[string]float64{"dairy": 0.85, "produce": 0.92},
			LastPurchaseDays:  2,
			OrderCount30d:     7,
			ConversionRate30d: 0.25,
			AvgBasketCents:    1950.5,
			Segment:           "HIGH_VALUE",
			UpdatedAt:         time.Now(),
		},
	}

	result, err := pool.SyncUserFeatures(context.Background(), "job-sync-100", "sub-1", records)
	require.NoError(t, err)
	assert.Equal(t, int64(1), result.RowsProcessed)
	assert.Equal(t, "COMPLETED", result.Status)

	client.mu.RLock()
	defer client.mu.RUnlock()

	assert.NotContains(t, client.hashes, "user:features:temp:job-sync-100:user-456")
	prodHash, exists := client.hashes["user:features:user-456"]
	require.True(t, exists)
	assert.Equal(t, "12500.50", prodHash["clv"])
	assert.Equal(t, "HIGH_VALUE", prodHash["segment"])
	assert.Equal(t, 2, prodHash["last_purchase_days"])
	assert.Equal(t, 7, prodHash["order_count_30d"])
	assert.Equal(t, "0.250000", prodHash["conversion_rate_30d"])
	assert.Equal(t, "1950.50", prodHash["avg_basket_cents"])
	assert.Contains(t, prodHash["category_affinity"], "dairy")
}

func TestSyncCustomerFraudScores36HourTTL(t *testing.T) {
	client := newMockRedisClient()
	pool := NewWorkerPool(WorkerPoolConfig{
		Concurrency:     5,
		RateLimitPerSec: 5000,
		RedisClient:     client,
	})
	defer pool.Close()

	scores := []CustomerFraudScoreRecord{
		{
			UserID:         "user-suspicious-99",
			FraudRiskScore: 0.94,
			ModelVersion:   "v2.1-prod",
			RiskFactors:    []string{"VELOCITY_SPIKE", "IP_GEO_MISMATCH"},
			ScoredAt:       time.Now(),
		},
	}

	result, err := pool.SyncCustomerFraudScores(context.Background(), "job-fraud-101", "sub-fraud-1", scores)
	require.NoError(t, err)
	assert.Equal(t, int64(1), result.RowsProcessed)
	assert.Equal(t, "COMPLETED", result.Status)

	client.mu.RLock()
	defer client.mu.RUnlock()

	assert.NotContains(t, client.values, "fraud:score:temp:job-fraud-101:user-suspicious-99")
	val, exists := client.values["fraud:score:user-suspicious-99"]
	require.True(t, exists)
	assert.Contains(t, val, "0.94")
	assert.Contains(t, val, "VELOCITY_SPIKE")

	ttl, hasTTL := client.ttls["fraud:score:user-suspicious-99"]
	require.True(t, hasTTL)
	assert.Equal(t, 36*time.Hour, ttl)
}

func TestSyncDarkStoreInventoryParallelConcurrency(t *testing.T) {
	client := newMockRedisClient()
	pool := NewWorkerPool(WorkerPoolConfig{
		Concurrency:     10,
		RateLimitPerSec: 10000,
		RedisClient:     client,
	})
	defer pool.Close()

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

		time.Sleep(5 * time.Millisecond)
		return []InventoryRecord{
			{StoreID: storeID, SKU: "SKU-APPLE", Available: 100, Reserved: 5, UpdatedAt: time.Now()},
			{StoreID: storeID, SKU: "SKU-MILK", Available: 50, Reserved: 0, UpdatedAt: time.Now()},
		}, nil
	}

	result, err := pool.SyncDarkStoreInventoryParallel(context.Background(), "job-inv-500", "sub-inv-1", storeIDs, fetchInventory)
	require.NoError(t, err)
	assert.Equal(t, 50, result.StoresSynced)
	assert.Equal(t, int64(100), result.RowsProcessed)
	assert.Equal(t, "COMPLETED", result.Status)
	assert.GreaterOrEqual(t, maxObservedWorkers, int64(2))
	assert.LessOrEqual(t, maxObservedWorkers, int64(10))

	client.mu.RLock()
	defer client.mu.RUnlock()
	for _, storeID := range storeIDs {
		assert.Contains(t, client.hashes, fmt.Sprintf("store:inventory:%s", storeID))
	}
}

func TestSyncDarkStoreInventoryParallelCancellationMarksCancelled(t *testing.T) {
	capture := &auditCaptureDB{}
	db := sql.OpenDB(capture)
	defer db.Close()

	pool := NewWorkerPool(WorkerPoolConfig{
		Concurrency:     4,
		RateLimitPerSec: 5000,
		RedisClient:     newMockRedisClient(),
		DB:              db,
	})
	defer pool.Close()

	canceledCtx, cancel := context.WithCancel(context.Background())
	cancel()

	res, err := pool.SyncDarkStoreInventoryParallel(canceledCtx, "job-inv-cancel", "sub-inv-1", []string{"store-1", "store-2"}, func(context.Context, string) ([]InventoryRecord, error) {
		return nil, nil
	})
	require.ErrorIs(t, err, context.Canceled)
	assert.Equal(t, "CANCELLED", res.Status)

	capture.mu.Lock()
	defer capture.mu.Unlock()
	require.Len(t, capture.execs, 1)
	assert.Equal(t, "CANCELLED", capture.execs[0].args[4].Value)
}

func TestRateLimiter(t *testing.T) {
	rl := NewRateLimiter(100)
	ctx := context.Background()

	for i := 0; i < 10; i++ {
		require.NoError(t, rl.Acquire(ctx))
	}
	rl.Stop()
	rl.Stop()
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
	require.True(t, hasTTL)
	assert.Equal(t, 36*time.Hour, ttl)
}

func TestRecordAuditPersistsCancelledRowWithCanceledContext(t *testing.T) {
	capture := &auditCaptureDB{}
	db := sql.OpenDB(capture)
	defer db.Close()

	pool := NewWorkerPool(WorkerPoolConfig{
		Concurrency:     5,
		RateLimitPerSec: 5000,
		RedisClient:     newMockRedisClient(),
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
	require.Len(t, capture.execs, 1)
	exec := capture.execs[0]
	assert.NoError(t, exec.ctxErr)
	assert.True(t, exec.hasDeadline)
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

func TestWorkerPoolNilGuards(t *testing.T) {
	pool := NewWorkerPool(WorkerPoolConfig{Concurrency: 2, RateLimitPerSec: 100})
	defer pool.Close()

	res, err := pool.SyncDarkStoreInventoryParallel(context.Background(), "job-nil", "sub-nil", []string{"s1"}, nil)
	require.Error(t, err)
	assert.Equal(t, "FAILED", res.Status)

	res, err = pool.SyncUserFeatures(context.Background(), "job-nil-uf", "sub-nil", []UserFeatureRecord{{UserID: "u1"}})
	require.Error(t, err)
	assert.Equal(t, "FAILED", res.Status)
}
