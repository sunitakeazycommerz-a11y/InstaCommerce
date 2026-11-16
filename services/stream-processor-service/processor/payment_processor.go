package processor

// Processes payment.events
// Computes: payment success rate (per method, 5-min window),
// revenue tracking, refund rate monitoring

import (
	"context"
	"fmt"
	"log/slog"
	"strings"
	"sync"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promauto"
	"github.com/redis/go-redis/v9"
)

type PaymentEvent struct {
	EventType   string    `json:"eventType"` // PaymentAuthorized, PaymentCaptured, PaymentInitiated, PaymentSuccess, PaymentFailed, PaymentRefunded, RefundInitiated, RefundCompleted
	PaymentID   string    `json:"paymentId"`
	OrderID     string    `json:"orderId"`
	AmountCents int64     `json:"amountCents"`
	Method      string    `json:"method"` // upi, card, wallet, cod
	FailureCode string    `json:"failureCode,omitempty"`
	Timestamp   time.Time `json:"timestamp"`
}

func (e *PaymentEvent) UnmarshalJSON(data []byte) error {
	meta, body, err := unwrapEnvelope(data)
	if err != nil {
		return err
	}
	sources := []map[string]any{body, meta}

	e.EventType = normalizePaymentEventType(firstString(sources, "eventType", "event_type", "type"))
	e.PaymentID = firstString(sources, "paymentId", "payment_id", "aggregateId", "aggregate_id", "id")
	e.OrderID = firstString(sources, "orderId", "order_id")
	e.Method = firstString(sources, "method", "paymentMethod", "payment_method")
	e.FailureCode = firstString(sources, "failureCode", "failure_code", "errorCode", "error_code", "reason")

	if amt, ok := firstInt64(sources, "amountCents", "amount_cents", "refundedCents", "refunded_cents", "amount"); ok {
		e.AmountCents = amt
	}
	if ts, ok := firstTime(sources, "timestamp", "eventTime", "event_time", "createdAt", "created_at"); ok {
		e.Timestamp = ts
	}
	return nil
}

func normalizePaymentEventType(raw string) string {
	switch strings.ToUpper(strings.TrimSpace(raw)) {
	case "PAYMENTAUTHORIZED", "PAYMENT_AUTHORIZED":
		return "PaymentAuthorized"
	case "PAYMENTCAPTURED", "PAYMENT_CAPTURED":
		return "PaymentCaptured"
	case "PAYMENTSUCCESS", "PAYMENT_SUCCESS":
		return "PaymentSuccess"
	case "PAYMENTINITIATED", "PAYMENT_INITIATED":
		return "PaymentInitiated"
	case "PAYMENTFAILED", "PAYMENT_FAILED":
		return "PaymentFailed"
	case "PAYMENTREFUNDED", "PAYMENT_REFUNDED":
		return "PaymentRefunded"
	case "REFUNDINITIATED", "REFUND_INITIATED":
		return "RefundInitiated"
	case "REFUNDCOMPLETED", "REFUND_COMPLETED":
		return "RefundCompleted"
	default:
		return strings.TrimSpace(raw)
	}
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

var (
	paymentMetricsInstance *paymentMetrics
	paymentMetricsOnce     sync.Once
)

func newPaymentMetrics() *paymentMetrics {
	paymentMetricsOnce.Do(func() {
		paymentMetricsInstance = &paymentMetrics{
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
	})
	return paymentMetricsInstance
}

type PaymentProcessor struct {
	redis   *redis.Client
	logger  *slog.Logger
	metrics *paymentMetrics

	// Sliding window for success rate per method
	windows     map[string][]paymentWindowEntry // method -> entries
	mu          sync.RWMutex
	methodGuard *labelCardinalityGuard
	codeGuard   *labelCardinalityGuard
	eventGuard  *labelCardinalityGuard
}

func NewPaymentProcessor(rdb *redis.Client, logger *slog.Logger) *PaymentProcessor {
	if logger == nil {
		logger = slog.Default()
	}
	return &PaymentProcessor{
		redis:       rdb,
		logger:      logger.With("processor", "payment"),
		metrics:     newPaymentMetrics(),
		windows:     make(map[string][]paymentWindowEntry),
		methodGuard: newLabelCardinalityGuard(64),
		codeGuard:   newLabelCardinalityGuard(128),
		eventGuard:  newLabelCardinalityGuard(64),
	}
}

func (p *PaymentProcessor) Process(ctx context.Context, event PaymentEvent) error {
	event.EventType = normalizePaymentEventType(event.EventType)
	methodLabel := p.methodGuard.normalize(event.Method, "unknown")
	eventLabel := p.eventGuard.normalize(event.EventType, "unknown")

	p.logger.Info("processing payment event",
		"eventType", event.EventType,
		"paymentId", event.PaymentID,
		"method", methodLabel,
	)

	p.metrics.paymentsTotal.WithLabelValues(eventLabel, methodLabel).Inc()

	switch event.EventType {
	case "PaymentSuccess", "PaymentCaptured":
		p.metrics.revenueTotal.Add(float64(event.AmountCents))
		p.recordWindowEntry(methodLabel, true)
	case "PaymentFailed":
		p.recordWindowEntry(methodLabel, false)
		if event.FailureCode != "" {
			codeLabel := p.codeGuard.normalize(event.FailureCode, "unknown")
			p.metrics.failuresByCode.WithLabelValues(codeLabel, methodLabel).Inc()
		}
	case "RefundInitiated":
		p.metrics.refundsTotal.WithLabelValues("initiated").Inc()
	case "RefundCompleted", "PaymentRefunded":
		p.metrics.refundsTotal.WithLabelValues("completed").Inc()
	}

	if p.redis == nil {
		return nil
	}

	pipe := p.redis.Pipeline()
	now := time.Now()
	minuteKey := now.Format("2006-01-02T15:04")

	switch event.EventType {
	case "PaymentAuthorized", "PaymentInitiated":
		authKey := fmt.Sprintf("payments:authorized:%s:%s", methodLabel, minuteKey)
		pipe.Incr(ctx, authKey)
		pipe.Expire(ctx, authKey, redisMetricsTTL)

	case "PaymentSuccess", "PaymentCaptured":
		revenueKey := fmt.Sprintf("revenue:total:%s", now.Format("2006-01-02"))
		pipe.IncrBy(ctx, revenueKey, event.AmountCents)
		pipe.Expire(ctx, revenueKey, 48*time.Hour)

		methodKey := fmt.Sprintf("payments:success:%s:%s", methodLabel, minuteKey)
		pipe.Incr(ctx, methodKey)
		pipe.Expire(ctx, methodKey, redisMetricsTTL)

	case "PaymentFailed":
		failKey := fmt.Sprintf("payments:failed:%s:%s", methodLabel, minuteKey)
		pipe.Incr(ctx, failKey)
		pipe.Expire(ctx, failKey, redisMetricsTTL)

	case "RefundInitiated":
		refundKey := fmt.Sprintf("refunds:initiated:%s", minuteKey)
		pipe.Incr(ctx, refundKey)
		pipe.Expire(ctx, refundKey, redisMetricsTTL)

	case "RefundCompleted", "PaymentRefunded":
		refundKey := fmt.Sprintf("refunds:completed:%s", minuteKey)
		pipe.Incr(ctx, refundKey)
		pipe.Expire(ctx, refundKey, redisMetricsTTL)
	}

	if _, err := pipe.Exec(ctx); err != nil {
		p.metrics.processingErrors.Inc()
		return fmt.Errorf("redis pipeline exec: %w", err)
	}

	return nil
}

// recordWindowEntry adds an entry to the 5-minute sliding window and updates the success rate gauge.
func (p *PaymentProcessor) recordWindowEntry(method string, success bool) {
	if method == "" {
		method = "unknown"
	}
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
