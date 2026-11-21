package subscription

import (
	"context"
	"database/sql"
	"log/slog"
	"time"

	"github.com/redis/go-redis/v9"
)

// RedisClient abstracts Redis operations for testability and mock injection.
type RedisClient interface {
	HSet(ctx context.Context, key string, values ...any) *redis.IntCmd
	Rename(ctx context.Context, key, newKey string) *redis.StatusCmd
	Set(ctx context.Context, key string, value any, expiration time.Duration) *redis.StatusCmd
	Get(ctx context.Context, key string) *redis.StringCmd
	Expire(ctx context.Context, key string, expiration time.Duration) *redis.BoolCmd
	Del(ctx context.Context, keys ...string) *redis.IntCmd
	Eval(ctx context.Context, script string, keys []string, args ...any) *redis.Cmd
	Ping(ctx context.Context) *redis.StatusCmd
}

// UserFeatureRecord represents customer lifetime value and category affinity features.
type UserFeatureRecord struct {
	UserID            string             `json:"user_id"`
	CLV               float64            `json:"clv"`
	CategoryAffinity  map[string]float64 `json:"category_affinity"`
	LastPurchaseDays  int                `json:"last_purchase_days"`
	OrderCount30d     int                `json:"order_count_30d"`
	ConversionRate30d float64            `json:"conversion_rate_30d"`
	AvgBasketCents    float64            `json:"avg_basket_cents"`
	Segment           string             `json:"segment"`
	UpdatedAt         time.Time          `json:"updated_at"`
}

// CustomerFraudScoreRecord represents fraud risk score sync payload.
type CustomerFraudScoreRecord struct {
	UserID         string    `json:"user_id"`
	FraudRiskScore float64   `json:"fraud_risk_score"`
	ModelVersion   string    `json:"model_version"`
	RiskFactors    []string  `json:"risk_factors"`
	ScoredAt       time.Time `json:"scored_at"`
}

// InventoryRecord represents per-SKU inventory levels for a dark store.
type InventoryRecord struct {
	StoreID   string    `json:"store_id"`
	SKU       string    `json:"sku"`
	Available int       `json:"available"`
	Reserved  int       `json:"reserved"`
	UpdatedAt time.Time `json:"updated_at"`
}

// SyncAuditEntry represents a sync execution audit log record.
type SyncAuditEntry struct {
	JobID          string        `json:"job_id"`
	SubscriptionID string        `json:"subscription_id"`
	SyncType       string        `json:"sync_type"`
	RowsProcessed  int64         `json:"rows_processed"`
	Status         string        `json:"status"`
	Duration       time.Duration `json:"duration"`
	ErrorMessage   string        `json:"error_message"`
	CreatedAt      time.Time     `json:"created_at"`
}

// SyncResult encapsulates the outcome of a reverse ETL sync run.
type SyncResult struct {
	JobID         string
	SyncType      string
	RowsProcessed int64
	StoresSynced  int
	Duration      time.Duration
	Status        string
	Error         error
}

// WorkerPoolConfig configures WorkerPool options.
type WorkerPoolConfig struct {
	Concurrency     int
	RateLimitPerSec int
	RedisClient     RedisClient
	DB              *sql.DB
	Logger          *slog.Logger
	RetryPolicy     RetryPolicy
}
