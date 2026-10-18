// Package subscription provides worker orchestration for reverse ETL pipelines,
// synchronizing warehouse features, dark store inventory, and fraud risk scores
// from BigQuery to operational Redis caches with idempotency and rate limiting.
package subscription

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"log/slog"
	"sync"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promauto"
	"github.com/redis/go-redis/v9"
)

// RedisClient abstracts Redis commands for reverse ETL operations and test isolation.
type RedisClient interface {
	HSet(ctx context.Context, key string, values ...any) *redis.IntCmd
	Rename(ctx context.Context, key, newKey string) *redis.StatusCmd
	Set(ctx context.Context, key string, value any, expiration time.Duration) *redis.StatusCmd
	Get(ctx context.Context, key string) *redis.StringCmd
	Expire(ctx context.Context, key string, expiration time.Duration) *redis.BoolCmd
	Del(ctx context.Context, keys ...string) *redis.IntCmd
	Ping(ctx context.Context) *redis.StatusCmd
}

// UserFeatureRecord models customer lifetime value and affinity features synced to Redis.
type UserFeatureRecord struct {
	UserID           string             `json:"user_id"`
	CLV              float64            `json:"clv"`
	CategoryAffinity map[string]float64 `json:"category_affinity"`
	LastPurchaseDays int                `json:"last_purchase_days"`
	Segment          string             `json:"segment"`
	UpdatedAt        time.Time          `json:"updated_at"`
}

// CustomerFraudScoreRecord models fraud risk scores synced from ml_models.customer_fraud_scores.
type CustomerFraudScoreRecord struct {
	UserID         string    `json:"user_id"`
	FraudRiskScore float64   `json:"fraud_risk_score"`
	ModelVersion   string    `json:"model_version"`
	RiskFactors    []string  `json:"risk_factors"`
	ScoredAt       time.Time `json:"scored_at"`
}

// InventoryRecord models dark store SKU inventory availability.
type InventoryRecord struct {
	StoreID   string    `json:"store_id"`
	SKU       string    `json:"sku"`
	Available int       `json:"available"`
	Reserved  int       `json:"reserved"`
	UpdatedAt time.Time `json:"updated_at"`
}

// SyncAuditEntry captures metadata audit entries for reverse ETL sync runs.
type SyncAuditEntry struct {
	JobID          string    `json:"job_id"`
	SubscriptionID string    `json:"subscription_id"`
	SyncType       string    `json:"sync_type"`
	RowsProcessed  int64     `json:"rows_processed"`
	Status         string    `json:"status"`
	DurationMs     int64     `json:"duration_ms"`
	ErrorMessage   string    `json:"error_message,omitempty"`
	CreatedAt      time.Time `json:"created_at"`
}

// SyncResult summarizes the outcome of a reverse ETL batch sync.
type SyncResult struct {
	JobID         string        `json:"job_id"`
	SyncType      string        `json:"sync_type"`
	RowsProcessed int64         `json:"rows_processed"`
	StoresSynced  int           `json:"stores_synced,omitempty"`
	Duration      time.Duration `json:"duration"`
	Status        string        `json:"status"`
	Error         error         `json:"-"`
}

// RateLimiter provides token-bucket rate limiting to prevent saturating Redis.
type RateLimiter struct {
	tokens chan struct{}
	stop   chan struct{}
}

// NewRateLimiter creates a token bucket releasing up to rps tokens per second.
func NewRateLimiter(rps int) *RateLimiter {
	if rps <= 0 {
		rps = 1000
	}
	rl := &RateLimiter{
		tokens: make(chan struct{}, rps),
		stop:   make(chan struct{}),
	}
	// Initial capacity
	for i := 0; i < rps; i++ {
		select {
		case rl.tokens <- struct{}{}:
		default:
		}
	}

	interval := time.Second / time.Duration(rps)
	if interval < time.Microsecond {
		interval = time.Microsecond
	}

	go func() {
		ticker := time.NewTicker(interval)
		defer ticker.Stop()
		for {
			select {
			case <-rl.stop:
				return
			case <-ticker.C:
				select {
				case rl.tokens <- struct{}{}:
				default:
				}
			}
		}
	}()

	return rl
}

// Acquire waits for a token or context cancellation.
func (rl *RateLimiter) Acquire(ctx context.Context) error {
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-rl.tokens:
		return nil
	}
}

// Stop stops the rate limiter background ticker.
func (rl *RateLimiter) Stop() {
	select {
	case <-rl.stop:
	default:
		close(rl.stop)
	}
}

// WorkerMetrics tracks Prometheus operational metrics for reverse ETL workers.
type WorkerMetrics struct {
	StoreSyncDuration *prometheus.HistogramVec
	SyncRecordsTotal  *prometheus.CounterVec
	SyncErrorsTotal   *prometheus.CounterVec
}

var (
	metricsOnce    sync.Once
	defaultMetrics *WorkerMetrics
)

// InitWorkerMetrics registers and returns Prometheus metrics for reverse ETL.
func InitWorkerMetrics() *WorkerMetrics {
	metricsOnce.Do(func() {
		defaultMetrics = &WorkerMetrics{
			StoreSyncDuration: promauto.NewHistogramVec(
				prometheus.HistogramOpts{
					Name:    "reverse_etl_store_sync_duration_seconds",
					Help:    "Duration of dark store inventory sync in seconds per store",
					Buckets: []float64{0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30},
				},
				[]string{"store_id"},
			),
			SyncRecordsTotal: promauto.NewCounterVec(
				prometheus.CounterOpts{
					Name: "reverse_etl_synced_records_total",
					Help: "Total number of records synced to operational caches",
				},
				[]string{"sync_type", "status"},
			),
			SyncErrorsTotal: promauto.NewCounterVec(
				prometheus.CounterOpts{
					Name: "reverse_etl_sync_errors_total",
					Help: "Total number of errors encountered during reverse ETL sync",
				},
				[]string{"sync_type", "error_type"},
			),
		}
	})
	return defaultMetrics
}

// WorkerPool manages parallel reverse ETL sync pipelines with worker pools and rate limiting.
type WorkerPool struct {
	concurrency int
	rateLimiter *RateLimiter
	redisClient RedisClient
	db          *sql.DB
	metrics     *WorkerMetrics
	logger      *slog.Logger
}

// WorkerPoolConfig configures WorkerPool options.
type WorkerPoolConfig struct {
	Concurrency     int
	RateLimitPerSec int
	RedisClient     RedisClient
	DB              *sql.DB
	Logger          *slog.Logger
}

// NewWorkerPool initializes a new WorkerPool.
func NewWorkerPool(cfg WorkerPoolConfig) *WorkerPool {
	concurrency := cfg.Concurrency
	if concurrency <= 0 {
		concurrency = 10
	}
	rps := cfg.RateLimitPerSec
	if rps <= 0 {
		rps = 1000
	}
	logger := cfg.Logger
	if logger == nil {
		logger = slog.Default()
	}

	return &WorkerPool{
		concurrency: concurrency,
		rateLimiter: NewRateLimiter(rps),
		redisClient: cfg.RedisClient,
		db:          cfg.DB,
		metrics:     InitWorkerMetrics(),
		logger:      logger,
	}
}

// Close releases rate limiter resources.
func (wp *WorkerPool) Close() {
	if wp.rateLimiter != nil {
		wp.rateLimiter.Stop()
	}
}

// SyncUserFeatures syncs customer lifetime value (CLV) and category affinity scores
// to Redis using temporary keys and atomic RENAME for idempotency (Issue #649).
func (wp *WorkerPool) SyncUserFeatures(ctx context.Context, jobID, subscriptionID string, records []UserFeatureRecord) (SyncResult, error) {
	start := time.Now()
	res := SyncResult{
		JobID:    jobID,
		SyncType: "user_features",
		Status:   "COMPLETED",
	}

	if len(records) == 0 {
		res.Duration = time.Since(start)
		return res, nil
	}

	var syncedCount int64
	for _, rec := range records {
		if err := ctx.Err(); err != nil {
			res.Status = "CANCELLED"
			res.Error = err
			wp.recordAudit(ctx, jobID, subscriptionID, "user_features", syncedCount, "CANCELLED", time.Since(start), err.Error())
			return res, err
		}

		if err := wp.rateLimiter.Acquire(ctx); err != nil {
			res.Status = "CANCELLED"
			res.Error = err
			wp.recordAudit(ctx, jobID, subscriptionID, "user_features", syncedCount, "CANCELLED", time.Since(start), err.Error())
			return res, err
		}

		tempKey := fmt.Sprintf("user:features:temp:%s:%s", jobID, rec.UserID)
		targetKey := fmt.Sprintf("user:features:%s", rec.UserID)

		affinityJSON, _ := json.Marshal(rec.CategoryAffinity)
		hsetCmd := wp.redisClient.HSet(ctx, tempKey,
			"clv", fmt.Sprintf("%.2f", rec.CLV),
			"category_affinity", string(affinityJSON),
			"last_purchase_days", rec.LastPurchaseDays,
			"segment", rec.Segment,
			"updated_at", rec.UpdatedAt.Format(time.RFC3339),
			"job_id", jobID,
		)
		if err := hsetCmd.Err(); err != nil {
			wp.metrics.SyncErrorsTotal.WithLabelValues("user_features", "redis_hset").Inc()
			res.Status = "FAILED"
			res.Error = fmt.Errorf("failed to write temp user features for user %s: %w", rec.UserID, err)
			wp.recordAudit(ctx, jobID, subscriptionID, "user_features", syncedCount, "FAILED", time.Since(start), res.Error.Error())
			return res, res.Error
		}

		// Atomically swap temp key to production key
		renameCmd := wp.redisClient.Rename(ctx, tempKey, targetKey)
		if err := renameCmd.Err(); err != nil {
			wp.metrics.SyncErrorsTotal.WithLabelValues("user_features", "redis_rename").Inc()
			res.Status = "FAILED"
			res.Error = fmt.Errorf("failed to atomically rename user features for user %s: %w", rec.UserID, err)
			wp.recordAudit(ctx, jobID, subscriptionID, "user_features", syncedCount, "FAILED", time.Since(start), res.Error.Error())
			return res, res.Error
		}

		syncedCount++
	}

	res.RowsProcessed = syncedCount
	res.Duration = time.Since(start)
	wp.metrics.SyncRecordsTotal.WithLabelValues("user_features", "success").Add(float64(syncedCount))

	wp.recordAudit(ctx, jobID, subscriptionID, "user_features", syncedCount, "COMPLETED", res.Duration, "")
	return res, nil
}

// SyncCustomerFraudScores queries/syncs fraud risk scores to Redis key fraud:score:{userId}
// with 36-hour TTL and atomic rename idempotency (Issue #659).
func (wp *WorkerPool) SyncCustomerFraudScores(ctx context.Context, jobID, subscriptionID string, scores []CustomerFraudScoreRecord) (SyncResult, error) {
	start := time.Now()
	res := SyncResult{
		JobID:    jobID,
		SyncType: "fraud_risk_scores",
		Status:   "COMPLETED",
	}

	const fraudScoreTTL = 36 * time.Hour

	var syncedCount int64
	for _, sc := range scores {
		if err := ctx.Err(); err != nil {
			res.Status = "CANCELLED"
			res.Error = err
			wp.recordAudit(ctx, jobID, subscriptionID, "fraud_risk_scores", syncedCount, "CANCELLED", time.Since(start), err.Error())
			return res, err
		}

		if err := wp.rateLimiter.Acquire(ctx); err != nil {
			res.Status = "CANCELLED"
			res.Error = err
			wp.recordAudit(ctx, jobID, subscriptionID, "fraud_risk_scores", syncedCount, "CANCELLED", time.Since(start), err.Error())
			return res, err
		}

		tempKey := fmt.Sprintf("fraud:score:temp:%s:%s", jobID, sc.UserID)
		targetKey := fmt.Sprintf("fraud:score:%s", sc.UserID)

		scorePayload, _ := json.Marshal(map[string]any{
			"user_id":          sc.UserID,
			"fraud_risk_score": sc.FraudRiskScore,
			"model_version":    sc.ModelVersion,
			"risk_factors":     sc.RiskFactors,
			"scored_at":        sc.ScoredAt.Format(time.RFC3339),
			"synced_at":        time.Now().Format(time.RFC3339),
			"job_id":           jobID,
		})

		// Write to temporary key with TTL
		setCmd := wp.redisClient.Set(ctx, tempKey, string(scorePayload), fraudScoreTTL)
		if err := setCmd.Err(); err != nil {
			wp.metrics.SyncErrorsTotal.WithLabelValues("fraud_risk_scores", "redis_set").Inc()
			res.Status = "FAILED"
			res.Error = fmt.Errorf("failed to write temp fraud score for user %s: %w", sc.UserID, err)
			wp.recordAudit(ctx, jobID, subscriptionID, "fraud_risk_scores", syncedCount, "FAILED", time.Since(start), res.Error.Error())
			return res, res.Error
		}

		// Atomically rename to production key
		renameCmd := wp.redisClient.Rename(ctx, tempKey, targetKey)
		if err := renameCmd.Err(); err != nil {
			wp.metrics.SyncErrorsTotal.WithLabelValues("fraud_risk_scores", "redis_rename").Inc()
			res.Status = "FAILED"
			res.Error = fmt.Errorf("failed to rename fraud score key for user %s: %w", sc.UserID, err)
			wp.recordAudit(ctx, jobID, subscriptionID, "fraud_risk_scores", syncedCount, "FAILED", time.Since(start), res.Error.Error())
			return res, res.Error
		}

		// Ensure 36-hour TTL persists on renamed key
		_ = wp.redisClient.Expire(ctx, targetKey, fraudScoreTTL)

		syncedCount++
	}

	res.RowsProcessed = syncedCount
	res.Duration = time.Since(start)
	wp.metrics.SyncRecordsTotal.WithLabelValues("fraud_risk_scores", "success").Add(float64(syncedCount))

	wp.recordAudit(ctx, jobID, subscriptionID, "fraud_risk_scores", syncedCount, "COMPLETED", res.Duration, "")
	return res, nil
}

// SyncDarkStoreInventoryParallel syncs inventory across multiple dark stores concurrently
// using a worker goroutine pool bounded by Concurrency (Issue #670).
func (wp *WorkerPool) SyncDarkStoreInventoryParallel(
	ctx context.Context,
	jobID, subscriptionID string,
	storeIDs []string,
	fetchStoreInventory func(ctx context.Context, storeID string) ([]InventoryRecord, error),
) (SyncResult, error) {
	start := time.Now()
	res := SyncResult{
		JobID:    jobID,
		SyncType: "dark_store_inventory",
		Status:   "COMPLETED",
	}

	if len(storeIDs) == 0 {
		res.Duration = time.Since(start)
		return res, nil
	}

	type storeJob struct {
		storeID string
	}

	jobCh := make(chan storeJob, len(storeIDs))
	for _, id := range storeIDs {
		jobCh <- storeJob{storeID: id}
	}
	close(jobCh)

	var wg sync.WaitGroup
	var mu sync.Mutex
	var totalRows int64
	var storesSynced int
	var firstErr error

	numWorkers := wp.concurrency
	if numWorkers > len(storeIDs) {
		numWorkers = len(storeIDs)
	}

	for i := 0; i < numWorkers; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for job := range jobCh {
				if ctx.Err() != nil {
					return
				}

				storeStart := time.Now()
				items, err := fetchStoreInventory(ctx, job.storeID)
				if err != nil {
					mu.Lock()
					if firstErr == nil {
						firstErr = fmt.Errorf("failed fetching inventory for store %s: %w", job.storeID, err)
					}
					mu.Unlock()
					continue
				}

				// Write inventory to temp hash then rename
				tempKey := fmt.Sprintf("store:inventory:temp:%s:%s", jobID, job.storeID)
				targetKey := fmt.Sprintf("store:inventory:%s", job.storeID)

				if len(items) > 0 {
					var kvPairs []any
					for _, item := range items {
						if err := wp.rateLimiter.Acquire(ctx); err != nil {
							return
						}
						kvPairs = append(kvPairs, item.SKU, fmt.Sprintf("%d:%d", item.Available, item.Reserved))
					}

					if err := wp.redisClient.HSet(ctx, tempKey, kvPairs...).Err(); err != nil {
						wp.metrics.SyncErrorsTotal.WithLabelValues("dark_store_inventory", "redis_hset").Inc()
						mu.Lock()
						if firstErr == nil {
							firstErr = fmt.Errorf("failed to write inventory for store %s: %w", job.storeID, err)
						}
						mu.Unlock()
						continue
					}

					if err := wp.redisClient.Rename(ctx, tempKey, targetKey).Err(); err != nil {
						wp.metrics.SyncErrorsTotal.WithLabelValues("dark_store_inventory", "redis_rename").Inc()
						mu.Lock()
						if firstErr == nil {
							firstErr = fmt.Errorf("failed to rename inventory for store %s: %w", job.storeID, err)
						}
						mu.Unlock()
						continue
					}
				}

				storeDuration := time.Since(storeStart).Seconds()
				wp.metrics.StoreSyncDuration.WithLabelValues(job.storeID).Observe(storeDuration)

				mu.Lock()
				totalRows += int64(len(items))
				storesSynced++
				mu.Unlock()
			}
		}()
	}

	wg.Wait()

	res.RowsProcessed = totalRows
	res.StoresSynced = storesSynced
	res.Duration = time.Since(start)

	if firstErr != nil {
		res.Status = "FAILED"
		res.Error = firstErr
		wp.recordAudit(ctx, jobID, subscriptionID, "dark_store_inventory", totalRows, "FAILED", res.Duration, firstErr.Error())
		return res, firstErr
	}

	wp.metrics.SyncRecordsTotal.WithLabelValues("dark_store_inventory", "success").Add(float64(totalRows))
	wp.recordAudit(ctx, jobID, subscriptionID, "dark_store_inventory", totalRows, "COMPLETED", res.Duration, "")
	return res, nil
}

// recordAudit persists sync audit entries to the metadata audit table (Issue #649).
func (wp *WorkerPool) recordAudit(ctx context.Context, jobID, subscriptionID, syncType string, rows int64, status string, duration time.Duration, errMsg string) {
	if wp.db == nil {
		return
	}

	const query = `
		INSERT INTO reverse_etl_sync_audit (
			job_id, subscription_id, sync_type, rows_processed,
			status, duration_ms, error_message, created_at
		) VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
		ON CONFLICT (job_id) DO UPDATE SET
			rows_processed = EXCLUDED.rows_processed,
			status = EXCLUDED.status,
			duration_ms = EXCLUDED.duration_ms,
			error_message = EXCLUDED.error_message
	`

	_, err := wp.db.ExecContext(ctx, query,
		jobID, subscriptionID, syncType, rows,
		status, duration.Milliseconds(), errMsg, time.Now(),
	)
	if err != nil {
		wp.logger.Warn("failed to write reverse ETL audit log (non-fatal)", "error", err, "job_id", jobID)
	}
}
