package subscription

import (
	"context"
	"sync"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promauto"
)

// RateLimiter implements a non-blocking token bucket to enforce operations per second.
type RateLimiter struct {
	rps      int
	tokens   chan struct{}
	stop     chan struct{}
	stopOnce sync.Once
}

// NewRateLimiter creates a token bucket rate limiter capped at rps tokens/sec.
func NewRateLimiter(rps int) *RateLimiter {
	if rps <= 0 {
		rps = 1000
	}
	rl := &RateLimiter{
		rps:    rps,
		tokens: make(chan struct{}, rps),
		stop:   make(chan struct{}),
	}
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
	if rl == nil {
		return nil
	}
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-rl.tokens:
		return nil
	}
}

// Stop stops the rate limiter background ticker safely once.
func (rl *RateLimiter) Stop() {
	if rl == nil {
		return
	}
	rl.stopOnce.Do(func() {
		close(rl.stop)
	})
}

// WorkerMetrics tracks Prometheus operational metrics for reverse ETL workers.
type WorkerMetrics struct {
	StoreSyncDuration *prometheus.HistogramVec
	SyncRecordsTotal  *prometheus.CounterVec
	SyncErrorsTotal   *prometheus.CounterVec
	SyncRetriesTotal  *prometheus.CounterVec
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
			SyncRetriesTotal: promauto.NewCounterVec(
				prometheus.CounterOpts{
					Name: "reverse_etl_sync_retries_total",
					Help: "Total number of retry decisions classified by failure kind",
				},
				[]string{"sync_type", "classification"},
			),
		}
	})
	return defaultMetrics
}
