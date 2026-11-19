package subscription

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"time"
)

// WorkerPool manages parallel reverse ETL sync pipelines with worker pools and rate limiting.
type WorkerPool struct {
	concurrency int
	rateLimiter *RateLimiter
	redisClient RedisClient
	db          *sql.DB
	metrics     *WorkerMetrics
	logger      *slog.Logger
	retryPolicy RetryPolicy
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
		retryPolicy: cfg.RetryPolicy.normalized(),
	}
}

// Close releases rate limiter resources.
func (wp *WorkerPool) Close() {
	if wp.rateLimiter != nil {
		wp.rateLimiter.Stop()
	}
}

func (wp *WorkerPool) recordErrorMetric(syncType, errorType string) {
	if wp.metrics != nil && wp.metrics.SyncErrorsTotal != nil {
		wp.metrics.SyncErrorsTotal.WithLabelValues(syncType, errorType).Inc()
	}
}

func (wp *WorkerPool) recordSuccessMetric(syncType string, count int64) {
	if wp.metrics != nil && wp.metrics.SyncRecordsTotal != nil {
		wp.metrics.SyncRecordsTotal.WithLabelValues(syncType, "success").Add(float64(count))
	}
}

func (wp *WorkerPool) observeStoreDuration(storeID string, seconds float64) {
	if wp.metrics != nil && wp.metrics.StoreSyncDuration != nil {
		wp.metrics.StoreSyncDuration.WithLabelValues(storeID).Observe(seconds)
	}
}

func (wp *WorkerPool) runRedisOp(ctx context.Context, syncType string, op func(context.Context) error) error {
	_, err := ExecuteWithRetry(ctx, syncType, wp.metrics, wp.retryPolicy, op)
	return err
}

// SyncUserFeatures syncs customer lifetime value (CLV) and category affinity scores
// to Redis using temporary keys and atomic RENAME for idempotency.
func (wp *WorkerPool) SyncUserFeatures(ctx context.Context, jobID, subscriptionID string, records []UserFeatureRecord) (SyncResult, error) {
	start := time.Now()
	res := SyncResult{
		JobID:    jobID,
		SyncType: "user_features",
		Status:   "COMPLETED",
	}

	const userFeaturesTTL = 36 * time.Hour

	if len(records) == 0 {
		res.Duration = time.Since(start)
		return res, nil
	}
	if wp.redisClient == nil {
		res.Status = "FAILED"
		res.Error = errors.New("redis client not configured")
		res.Duration = time.Since(start)
		return res, res.Error
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

		affinityJSON, err := json.Marshal(rec.CategoryAffinity)
		if err != nil {
			wp.recordErrorMetric("user_features", "marshal")
			res.Status = "FAILED"
			res.Error = fmt.Errorf("failed to marshal category affinity for user %s: %w", rec.UserID, err)
			wp.recordAudit(ctx, jobID, subscriptionID, "user_features", syncedCount, "FAILED", time.Since(start), res.Error.Error())
			return res, res.Error
		}
		if err := wp.runRedisOp(ctx, "user_features", func(c context.Context) error {
			return wp.redisClient.HSet(c, tempKey,
				"clv", fmt.Sprintf("%.2f", rec.CLV),
				"category_affinity", string(affinityJSON),
				"last_purchase_days", rec.LastPurchaseDays,
				"order_count_30d", rec.OrderCount30d,
				"conversion_rate_30d", fmt.Sprintf("%.6f", rec.ConversionRate30d),
				"avg_basket_cents", fmt.Sprintf("%.2f", rec.AvgBasketCents),
				"segment", rec.Segment,
				"updated_at", rec.UpdatedAt.Format(time.RFC3339),
				"job_id", jobID,
			).Err()
		}); err != nil {
			wp.recordErrorMetric("user_features", "redis_hset")
			res.Status = "FAILED"
			res.Error = fmt.Errorf("failed to write temp user features for user %s: %w", rec.UserID, err)
			wp.recordAudit(ctx, jobID, subscriptionID, "user_features", syncedCount, "FAILED", time.Since(start), res.Error.Error())
			return res, res.Error
		}

		if err := wp.runRedisOp(ctx, "user_features", func(c context.Context) error {
			return wp.redisClient.Rename(c, tempKey, targetKey).Err()
		}); err != nil {
			_ = wp.redisClient.Del(ctx, tempKey)
			wp.recordErrorMetric("user_features", "redis_rename")
			res.Status = "FAILED"
			res.Error = fmt.Errorf("failed to atomically rename user features for user %s: %w", rec.UserID, err)
			wp.recordAudit(ctx, jobID, subscriptionID, "user_features", syncedCount, "FAILED", time.Since(start), res.Error.Error())
			return res, res.Error
		}

		if err := wp.runRedisOp(ctx, "user_features", func(c context.Context) error {
			return wp.redisClient.Expire(c, targetKey, userFeaturesTTL).Err()
		}); err != nil {
			wp.recordErrorMetric("user_features", "redis_expire")
			res.Status = "FAILED"
			res.Error = fmt.Errorf("failed to set TTL on user features for user %s: %w", rec.UserID, err)
			wp.recordAudit(ctx, jobID, subscriptionID, "user_features", syncedCount, "FAILED", time.Since(start), res.Error.Error())
			return res, res.Error
		}

		syncedCount++
	}

	res.RowsProcessed = syncedCount
	res.Duration = time.Since(start)
	wp.recordSuccessMetric("user_features", syncedCount)
	wp.recordAudit(ctx, jobID, subscriptionID, "user_features", syncedCount, "COMPLETED", res.Duration, "")
	return res, nil
}

// SyncCustomerFraudScores syncs fraud risk scores to Redis key fraud:score:{userId} with 36-hour TTL.
func (wp *WorkerPool) SyncCustomerFraudScores(ctx context.Context, jobID, subscriptionID string, scores []CustomerFraudScoreRecord) (SyncResult, error) {
	start := time.Now()
	res := SyncResult{
		JobID:    jobID,
		SyncType: "fraud_risk_scores",
		Status:   "COMPLETED",
	}

	const fraudScoreTTL = 36 * time.Hour

	if len(scores) == 0 {
		res.Duration = time.Since(start)
		return res, nil
	}
	if wp.redisClient == nil {
		res.Status = "FAILED"
		res.Error = errors.New("redis client not configured")
		res.Duration = time.Since(start)
		return res, res.Error
	}

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

		scorePayload, err := json.Marshal(map[string]any{
			"user_id":          sc.UserID,
			"fraud_risk_score": sc.FraudRiskScore,
			"model_version":    sc.ModelVersion,
			"risk_factors":     sc.RiskFactors,
			"scored_at":        sc.ScoredAt.Format(time.RFC3339),
			"synced_at":        time.Now().Format(time.RFC3339),
			"job_id":           jobID,
		})
		if err != nil {
			wp.recordErrorMetric("fraud_risk_scores", "marshal")
			res.Status = "FAILED"
			res.Error = fmt.Errorf("failed to marshal fraud score for user %s: %w", sc.UserID, err)
			wp.recordAudit(ctx, jobID, subscriptionID, "fraud_risk_scores", syncedCount, "FAILED", time.Since(start), res.Error.Error())
			return res, res.Error
		}

		if err := wp.runRedisOp(ctx, "fraud_risk_scores", func(c context.Context) error {
			return wp.redisClient.Set(c, tempKey, string(scorePayload), fraudScoreTTL).Err()
		}); err != nil {
			wp.recordErrorMetric("fraud_risk_scores", "redis_set")
			res.Status = "FAILED"
			res.Error = fmt.Errorf("failed to write temp fraud score for user %s: %w", sc.UserID, err)
			wp.recordAudit(ctx, jobID, subscriptionID, "fraud_risk_scores", syncedCount, "FAILED", time.Since(start), res.Error.Error())
			return res, res.Error
		}

		if err := wp.runRedisOp(ctx, "fraud_risk_scores", func(c context.Context) error {
			return wp.redisClient.Rename(c, tempKey, targetKey).Err()
		}); err != nil {
			_ = wp.redisClient.Del(ctx, tempKey)
			wp.recordErrorMetric("fraud_risk_scores", "redis_rename")
			res.Status = "FAILED"
			res.Error = fmt.Errorf("failed to rename fraud score key for user %s: %w", sc.UserID, err)
			wp.recordAudit(ctx, jobID, subscriptionID, "fraud_risk_scores", syncedCount, "FAILED", time.Since(start), res.Error.Error())
			return res, res.Error
		}

		if err := wp.runRedisOp(ctx, "fraud_risk_scores", func(c context.Context) error {
			return wp.redisClient.Expire(c, targetKey, fraudScoreTTL).Err()
		}); err != nil {
			wp.recordErrorMetric("fraud_risk_scores", "redis_expire")
			res.Status = "FAILED"
			res.Error = fmt.Errorf("failed to set TTL on fraud score for user %s: %w", sc.UserID, err)
			wp.recordAudit(ctx, jobID, subscriptionID, "fraud_risk_scores", syncedCount, "FAILED", time.Since(start), res.Error.Error())
			return res, res.Error
		}

		syncedCount++
	}

	res.RowsProcessed = syncedCount
	res.Duration = time.Since(start)
	wp.recordSuccessMetric("fraud_risk_scores", syncedCount)
	wp.recordAudit(ctx, jobID, subscriptionID, "fraud_risk_scores", syncedCount, "COMPLETED", res.Duration, "")
	return res, nil
}
