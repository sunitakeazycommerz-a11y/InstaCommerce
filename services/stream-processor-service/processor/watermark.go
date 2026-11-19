package processor

import (
	"fmt"
	"sync"
	"sync/atomic"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promauto"
	"github.com/segmentio/kafka-go"
)

// DefaultAllowedLateness is the default bounded out-of-order window before an event is flagged late.
const DefaultAllowedLateness = 5 * time.Minute

// WatermarkDecision reports event-time watermark progression and lateness evaluation.
type WatermarkDecision struct {
	EventTime    time.Time
	MaxEventTime time.Time
	Watermark    time.Time
	Lateness     time.Duration
	OutOfOrder   bool
	Late         bool
}

type watermarkMetrics struct {
	lateEventsTotal       *prometheus.CounterVec
	outOfOrderEventsTotal *prometheus.CounterVec
	watermarkLagSeconds   *prometheus.GaugeVec
	watermarkTimestampSec *prometheus.GaugeVec
}

var newWatermarkMetrics = sync.OnceValue(func() *watermarkMetrics {
	return &watermarkMetrics{
		lateEventsTotal: promauto.NewCounterVec(prometheus.CounterOpts{
			Name: "stream_late_events_total",
			Help: "Total events arriving behind the stream event-time watermark",
		}, []string{"stream", "action"}),
		outOfOrderEventsTotal: promauto.NewCounterVec(prometheus.CounterOpts{
			Name: "stream_out_of_order_events_total",
			Help: "Total events arriving with timestamp earlier than max observed event time",
		}, []string{"stream"}),
		watermarkLagSeconds: promauto.NewGaugeVec(prometheus.GaugeOpts{
			Name: "stream_watermark_lag_seconds",
			Help: "Observed event-time lag behind stream max event time in seconds",
		}, []string{"stream"}),
		watermarkTimestampSec: promauto.NewGaugeVec(prometheus.GaugeOpts{
			Name: "stream_watermark_timestamp_seconds",
			Help: "Current event-time watermark unix timestamp in seconds per stream",
		}, []string{"stream"}),
	}
})

type streamWatermarkState struct {
	maxEventTime time.Time
	lateCount    int64
	outOfOrder   int64
}

// WatermarkTracker tracks per-stream event-time watermarks and late-event counts.
type WatermarkTracker struct {
	mu              sync.RWMutex
	allowedLateness time.Duration
	streams         map[string]*streamWatermarkState
	streamGuard     *labelCardinalityGuard
	metrics         *watermarkMetrics
	totalLate       atomic.Int64
}

// NewWatermarkTracker creates a WatermarkTracker with the given allowed lateness window.
func NewWatermarkTracker(allowedLateness time.Duration) *WatermarkTracker {
	if allowedLateness < 0 {
		allowedLateness = DefaultAllowedLateness
	}
	return &WatermarkTracker{
		allowedLateness: allowedLateness,
		streams:         make(map[string]*streamWatermarkState),
		streamGuard:     newLabelCardinalityGuard(maxBoundedLabelValues),
		metrics:         newWatermarkMetrics(),
	}
}

// Observe advances or evaluates the event-time watermark for stream with action="accepted".
func (wt *WatermarkTracker) Observe(stream string, eventTime time.Time) WatermarkDecision {
	return wt.ObserveWithAction(stream, eventTime, false)
}

// ObserveWithAction evaluates eventTime against the stream watermark and records whether a late event was dropped.
func (wt *WatermarkTracker) ObserveWithAction(stream string, eventTime time.Time, dropIfLate bool) WatermarkDecision {
	if wt == nil || eventTime.IsZero() {
		return WatermarkDecision{EventTime: eventTime}
	}
	eventTime = eventTime.UTC()
	if stream == "" {
		stream = "default"
	}
	streamLabel := wt.streamGuard.normalize(stream, "default")

	wt.mu.Lock()
	st, ok := wt.streams[stream]
	if !ok {
		st = &streamWatermarkState{maxEventTime: eventTime}
		wt.streams[stream] = st
	}

	var lateness time.Duration
	outOfOrder := false
	if eventTime.After(st.maxEventTime) {
		st.maxEventTime = eventTime
	} else if eventTime.Before(st.maxEventTime) {
		lateness = st.maxEventTime.Sub(eventTime)
		outOfOrder = true
		st.outOfOrder++
	}

	maxObserved := st.maxEventTime
	watermark := maxObserved.Add(-wt.allowedLateness)
	isLate := eventTime.Before(watermark)
	if isLate {
		st.lateCount++
		wt.totalLate.Add(1)
	}
	wt.mu.Unlock()

	if wt.metrics != nil {
		wt.metrics.watermarkTimestampSec.WithLabelValues(streamLabel).Set(float64(watermark.Unix()))
		wt.metrics.watermarkLagSeconds.WithLabelValues(streamLabel).Set(lateness.Seconds())
		if outOfOrder {
			wt.metrics.outOfOrderEventsTotal.WithLabelValues(streamLabel).Inc()
		}
		if isLate {
			action := "accepted"
			if dropIfLate {
				action = "dropped"
			}
			wt.metrics.lateEventsTotal.WithLabelValues(streamLabel, action).Inc()
		}
	}

	return WatermarkDecision{
		EventTime:    eventTime,
		MaxEventTime: maxObserved,
		Watermark:    watermark,
		Lateness:     lateness,
		OutOfOrder:   outOfOrder,
		Late:         isLate,
	}
}

// Watermark returns the current watermark (maxEventTime - allowedLateness) for stream.
func (wt *WatermarkTracker) Watermark(stream string) (time.Time, bool) {
	if wt == nil {
		return time.Time{}, false
	}
	wt.mu.RLock()
	defer wt.mu.RUnlock()
	st, ok := wt.streams[stream]
	if !ok || st.maxEventTime.IsZero() {
		return time.Time{}, false
	}
	return st.maxEventTime.Add(-wt.allowedLateness), true
}

// MaxObserved returns the maximum event timestamp seen for stream.
func (wt *WatermarkTracker) MaxObserved(stream string) (time.Time, bool) {
	if wt == nil {
		return time.Time{}, false
	}
	wt.mu.RLock()
	defer wt.mu.RUnlock()
	st, ok := wt.streams[stream]
	if !ok || st.maxEventTime.IsZero() {
		return time.Time{}, false
	}
	return st.maxEventTime, true
}

// LateCount returns the number of late events observed for a specific stream.
func (wt *WatermarkTracker) LateCount(stream string) int64 {
	if wt == nil {
		return 0
	}
	wt.mu.RLock()
	defer wt.mu.RUnlock()
	st, ok := wt.streams[stream]
	if !ok {
		return 0
	}
	return st.lateCount
}

// TotalLateCount returns the total number of late events across all streams.
func (wt *WatermarkTracker) TotalLateCount() int64 {
	if wt == nil {
		return 0
	}
	return wt.totalLate.Load()
}

// ExtractMessageEventTime extracts an event timestamp from a Kafka message payload or header/metadata.
func ExtractMessageEventTime(msg kafka.Message) time.Time {
	if len(msg.Value) > 0 {
		meta, body, err := unwrapEnvelope(msg.Value)
		if err == nil {
			sources := []map[string]any{body, meta}
			if ts, ok := firstTime(
				sources,
				"eventTime", "event_time",
				"timestamp", "timestamp_ms", "timestampMs",
				"occurredAt", "occurred_at",
				"deliveredAt", "delivered_at",
				"placedAt", "placed_at",
				"createdAt", "created_at",
			); ok && !ts.IsZero() {
				return ts.UTC()
			}
		}
	}
	if !msg.Time.IsZero() {
		return msg.Time.UTC()
	}
	return time.Time{}
}

func streamPartitionKey(msg kafka.Message) string {
	if msg.Topic == "" {
		return "default"
	}
	return fmt.Sprintf("%s:%d", msg.Topic, msg.Partition)
}
