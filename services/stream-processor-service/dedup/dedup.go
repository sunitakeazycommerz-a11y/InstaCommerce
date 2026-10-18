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
	"sync"
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

var (
	metricsInstance *dedupMetrics
	metricsOnce     sync.Once
)

func getDedupMetrics() *dedupMetrics {
	metricsOnce.Do(func() {
		metricsInstance = &dedupMetrics{
			skippedTotal: promauto.NewCounterVec(prometheus.CounterOpts{
				Name: "dedup_skipped_total",
				Help: "Total duplicate messages skipped by topic",
			}, []string{"topic"}),

			errorsTotal: promauto.NewCounter(prometheus.CounterOpts{
				Name: "dedup_errors_total",
				Help: "Total dedup Redis check errors (fail-open)",
			}),
		}
	})
	return metricsInstance
}

// RedisClient interface abstraction allowing mock clients for unit tests.
type RedisClient interface {
	SetNX(ctx context.Context, key string, value interface{}, expiration time.Duration) *redis.BoolCmd
}

// Checker performs event-ID deduplication via Redis SET NX and optional Bloom filter.
type Checker struct {
	redis   RedisClient
	logger  *slog.Logger
	metrics *dedupMetrics
	bloom   *BloomFilter
	ttl     time.Duration
}

// NewChecker creates a deduplication checker backed by Redis.
func NewChecker(rdb RedisClient, logger *slog.Logger) *Checker {
	if logger == nil {
		logger = slog.Default()
	}
	return &Checker{
		redis:   rdb,
		logger:  logger.With("component", "dedup"),
		metrics: getDedupMetrics(),
		ttl:     keyTTL,
	}
}

// WithBloomFilter attaches an in-memory Bloom filter for fast-path membership tests.
func (c *Checker) WithBloomFilter(bf *BloomFilter) *Checker {
	c.bloom = bf
	return c
}

// WithCustomTTL overrides the default sliding window TTL.
func (c *Checker) WithCustomTTL(ttl time.Duration) *Checker {
	if ttl > 0 {
		c.ttl = ttl
	}
	return c
}

// IsDuplicate checks whether the message identified by (topic, partition, offset)
// has already been processed. It atomically marks the message as seen using
// Redis SET NX with a sliding window TTL (default 24 hours).
//
// Returns true if the message was already processed (duplicate).
// On Redis failure, returns false to allow processing (fail-open for availability).
func (c *Checker) IsDuplicate(ctx context.Context, topic string, partition int, offset int64) (bool, error) {
	key := fmt.Sprintf("dedup:%s:%d:%d", topic, partition, offset)

	if c.redis == nil {
		if c.bloom != nil {
			if c.bloom.Contains(key) {
				c.metrics.skippedTotal.WithLabelValues(topic).Inc()
				return true, nil
			}
			c.bloom.Add(key)
			return false, nil
		}
		return false, nil
	}

	ttl := c.ttl
	if ttl <= 0 {
		ttl = keyTTL
	}

	set, err := c.redis.SetNX(ctx, key, 1, ttl).Result()
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
		if c.bloom != nil {
			c.bloom.Add(key)
		}
		c.metrics.skippedTotal.WithLabelValues(topic).Inc()
		return true, nil
	}

	if c.bloom != nil {
		c.bloom.Add(key)
	}

	return false, nil
}

