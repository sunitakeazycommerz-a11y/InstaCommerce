package processor

// Processes payment.events
// Computes: payment success rate (per method, 5-min window),
// revenue tracking, refund rate monitoring

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

type PaymentEvent struct {
	EventType    string    `json:"eventType"` // PaymentInitiated, PaymentSuccess, PaymentFailed, RefundInitiated, RefundCompleted
	PaymentID    string    `json:"paymentId"`
	OrderID      string    `json:"orderId"`
	AmountCents  int64     `json:"amountCents"`
	Method       string    `json:"method"` // upi, card, wallet, cod
	FailureCode  string    `json:"failureCode,omitempty"`
	Timestamp    time.Time `json:"timestamp"`
}

type paymentWindowEntry struct {
	timestamp time.Time
	success   bool
}

type paymentMetrics struct {
	paymentsTotal    *prometheus.CounterVec
	revenueTotal     prometheus.Counter
	refundsTotal     *prometheus.CounterVec
	successRate      *prometheus.GaugeVec
	failuresByCode   *prometheus.CounterVec
	processingErrors prometheus.Counter
}

func newPaymentMetrics() *paymentMetrics {
	return &paymentMetrics{
		paymentsTotal: promauto.NewCounterVec(prometheus.CounterOpts{
			Name: "payments_total",
			Help: "Total payment events by type and method",
		}, []string{"event_type", "method"}),

		revenueTotal: promauto.NewCounter(prometheus.CounterOpts{
			Name: "payment_revenue_total_cents",
			Help: "Total successful payment revenue in cents",
		}),

		refundsTotal: promauto.NewCounterVec(prometheus.CounterOpts{
			Name: "refunds_total",
			Help: "Total refund events by type",
		}, []string{"event_type"}),

		successRate: promauto.NewGaugeVec(prometheus.GaugeOpts{
			Name: "payment_success_rate",
			Help: "Payment success rate per method (5-min window)",
		}, []string{"method"}),

		failuresByCode: promauto.NewCounterVec(prometheus.CounterOpts{
			Name: "payment_failures_by_code",
			Help: "Payment failures by failure code",
		}, []string{"failure_code", "method"}),

		processingErrors: promauto.NewCounter(prometheus.CounterOpts{
			Name: "payment_processing_errors_total",
			Help: "Total payment processing errors",
		}),
	}
}

type PaymentProcessor struct {
	redis   *redis.Client
	logger  *slog.Logger
	metrics *paymentMetrics

	// Sliding window for success rate per method
	windows map[string][]paymentWindowEntry // method -> entries
	mu      sync.RWMutex
}

func NewPaymentProcessor(rdb *redis.Client, logger *slog.Logger) *PaymentProcessor {
	return &PaymentProcessor{
		redis:   rdb,
		logger:  logger.With("processor", "payment"),
		metrics: newPaymentMetrics(),
		windows: make(map[string][]paymentWindowEntry),
	}
}

func (p *PaymentProcessor) Process(ctx context.Context, event PaymentEvent) error {
	p.logger.Info("processing payment event",
		"eventType", event.EventType,
		"paymentId", event.PaymentID,
		"method", event.Method,
	)

	p.metrics.paymentsTotal.WithLabelValues(event.EventType, event.Method).Inc()

	pipe := p.redis.Pipeline()
	now := time.Now()
	minuteKey := now.Format("2006-01-02T15:04")

	switch event.EventType {
	case "PaymentSuccess":
		p.metrics.revenueTotal.Add(float64(event.AmountCents))
		p.recordWindowEntry(event.Method, true)

		revenueKey := fmt.Sprintf("revenue:total:%s", now.Format("2006-01-02"))
		pipe.IncrBy(ctx, revenueKey, event.AmountCents)
		pipe.Expire(ctx, revenueKey, 48*time.Hour)

		methodKey := fmt.Sprintf("payments:success:%s:%s", event.Method, minuteKey)
		pipe.Incr(ctx, methodKey)
		pipe.Expire(ctx, methodKey, 2*time.Hour)

	case "PaymentFailed":
		p.recordWindowEntry(event.Method, false)

		if event.FailureCode != "" {
			p.metrics.failuresByCode.WithLabelValues(event.FailureCode, event.Method).Inc()
		}

		failKey := fmt.Sprintf("payments:failed:%s:%s", event.Method, minuteKey)
		pipe.Incr(ctx, failKey)
		pipe.Expire(ctx, failKey, 2*time.Hour)

	case "RefundInitiated":
		p.metrics.refundsTotal.WithLabelValues("initiated").Inc()

		refundKey := fmt.Sprintf("refunds:initiated:%s", minuteKey)
		pipe.Incr(ctx, refundKey)
		pipe.Expire(ctx, refundKey, 2*time.Hour)

	case "RefundCompleted":
		p.metrics.refundsTotal.WithLabelValues("completed").Inc()

		refundKey := fmt.Sprintf("refunds:completed:%s", minuteKey)
		pipe.Incr(ctx, refundKey)
		pipe.Expire(ctx, refundKey, 2*time.Hour)
	}

	if _, err := pipe.Exec(ctx); err != nil {
		p.metrics.processingErrors.Inc()
		return fmt.Errorf("redis pipeline exec: %w", err)
	}

	return nil
}

// recordWindowEntry adds an entry to the 5-minute sliding window and updates the success rate gauge.
func (p *PaymentProcessor) recordWindowEntry(method string, success bool) {
	p.mu.Lock()
	defer p.mu.Unlock()

	now := time.Now()
	cutoff := now.Add(-5 * time.Minute)

	entries := p.windows[method]

	// Evict old entries
	idx := 0
	for idx < len(entries) && entries[idx].timestamp.Before(cutoff) {
		idx++
	}
	entries = entries[idx:]

	// Append new entry
	entries = append(entries, paymentWindowEntry{timestamp: now, success: success})
	p.windows[method] = entries

	// Compute success rate
	if len(entries) == 0 {
		return
	}
	successCount := 0
	for _, e := range entries {
		if e.success {
			successCount++
		}
	}
	rate := float64(successCount) / float64(len(entries))
	p.metrics.successRate.WithLabelValues(method).Set(rate)
}
