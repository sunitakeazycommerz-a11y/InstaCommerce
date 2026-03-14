package dedup

// Event-ID deduplication using Redis SET NX.
// Prevents replayed Kafka messages from causing duplicate side effects
// (double-counting GMV, revenue, zone rider counts, etc.).
//
// Key format: dedup:{topic}:{partition}:{offset} with a 24-hour TTL.
// Fail-open: on Redis errors, processing is allowed to preserve availability.

import (
	"context"
	"fmt"
	"log/slog"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promauto"
	"github.com/redis/go-redis/v9"
)

const keyTTL = 24 * time.Hour

type dedupMetrics struct {
	skippedTotal *prometheus.CounterVec
	errorsTotal  prometheus.Counter
}

func newDedupMetrics() *dedupMetrics {
	return &dedupMetrics{
		skippedTotal: promauto.NewCounterVec(prometheus.CounterOpts{
			Name: "dedup_skipped_total",
			Help: "Total duplicate messages skipped by topic",
		}, []string{"topic"}),

		errorsTotal: promauto.NewCounter(prometheus.CounterOpts{
			Name: "dedup_errors_total",
			Help: "Total dedup Redis check errors (fail-open)",
		}),
	}
}

// Checker performs event-ID deduplication via Redis SET NX.
type Checker struct {
	redis   *redis.Client
	logger  *slog.Logger
	metrics *dedupMetrics
}

// NewChecker creates a deduplication checker backed by Redis.
func NewChecker(rdb *redis.Client, logger *slog.Logger) *Checker {
	return &Checker{
		redis:   rdb,
		logger:  logger.With("component", "dedup"),
		metrics: newDedupMetrics(),
	}
}

// IsDuplicate checks whether the message identified by (topic, partition, offset)
// has already been processed. It atomically marks the message as seen using
// Redis SET NX with a 24-hour TTL.
//
// Returns true if the message was already processed (duplicate).
// On Redis failure, returns false to allow processing (fail-open for availability).
func (c *Checker) IsDuplicate(ctx context.Context, topic string, partition int, offset int64) (bool, error) {
	key := fmt.Sprintf("dedup:%s:%d:%d", topic, partition, offset)

	set, err := c.redis.SetNX(ctx, key, 1, keyTTL).Result()
	if err != nil {
		c.metrics.errorsTotal.Inc()
		c.logger.Warn("dedup check failed, allowing processing",
			"topic", topic,
			"partition", partition,
			"offset", offset,
			"error", err,
		)
		return false, err
	}

	// SetNX returns true if the key was newly set (not a duplicate),
	// false if the key already existed (duplicate).
	if !set {
		c.metrics.skippedTotal.WithLabelValues(topic).Inc()
		return true, nil
	}

	return false, nil
}
