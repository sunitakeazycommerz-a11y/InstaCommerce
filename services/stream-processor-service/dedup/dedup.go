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
	Exists(ctx context.Context, keys ...string) *redis.IntCmd
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

func dedupKey(topic string, partition int, offset int64) string {
	return fmt.Sprintf("dedup:%s:%d:%d", topic, partition, offset)
}

func (c *Checker) effectiveTTL() time.Duration {
	if c.ttl > 0 {
		return c.ttl
	}
	return keyTTL
}

// CheckDuplicate performs a read-only check for whether (topic, partition, offset)
// was already marked processed. On Redis failure it fails open and returns (false, err).
func (c *Checker) CheckDuplicate(ctx context.Context, topic string, partition int, offset int64) (bool, error) {
	key := dedupKey(topic, partition, offset)

	if c.redis == nil {
		if c.bloom != nil && c.bloom.Contains(key) {
			c.metrics.skippedTotal.WithLabelValues(topic).Inc()
			return true, nil
		}
		return false, nil
	}

	count, err := c.redis.Exists(ctx, key).Result()
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
	if count > 0 {
		if c.bloom != nil {
			c.bloom.Add(key)
		}
		c.metrics.skippedTotal.WithLabelValues(topic).Inc()
		return true, nil
	}
	return false, nil
}

// MarkProcessed records (topic, partition, offset) as processed after handler success.
func (c *Checker) MarkProcessed(ctx context.Context, topic string, partition int, offset int64) error {
	key := dedupKey(topic, partition, offset)

	if c.bloom != nil {
		c.bloom.Add(key)
	}
	if c.redis == nil {
		return nil
	}

	if _, err := c.redis.SetNX(ctx, key, 1, c.effectiveTTL()).Result(); err != nil {
		c.metrics.errorsTotal.Inc()
		c.logger.Warn("dedup mark failed",
			"topic", topic,
			"partition", partition,
			"offset", offset,
			"error", err,
		)
		return err
	}
	return nil
}

// IsDuplicate checks whether the message identified by (topic, partition, offset)
// has already been processed and atomically marks it as seen using Redis SET NX.
//
// Prefer CheckDuplicate before processing followed by MarkProcessed on success so
// failed messages can be replayed after consumer restarts.
func (c *Checker) IsDuplicate(ctx context.Context, topic string, partition int, offset int64) (bool, error) {
	key := dedupKey(topic, partition, offset)

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

	set, err := c.redis.SetNX(ctx, key, 1, c.effectiveTTL()).Result()
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

