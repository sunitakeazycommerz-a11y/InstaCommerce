package observability

import (
	"net/http"
	"strconv"
	"time"

	"github.com/prometheus/client_golang/prometheus"
)

// HTTPMetrics holds standard Prometheus metrics for HTTP handlers.
// Every InstaCommerce Go service should register one instance per
// logical namespace (typically the service name).
type HTTPMetrics struct {
	// RequestCount counts total HTTP requests partitioned by method, path, and status.
	RequestCount *prometheus.CounterVec
	// RequestDuration observes request latency in seconds.
	RequestDuration *prometheus.HistogramVec
	// RequestSize observes request body sizes in bytes.
	RequestSize *prometheus.HistogramVec
	// ResponseSize observes response body sizes in bytes.
	ResponseSize *prometheus.HistogramVec
}

// NewHTTPMetrics creates and registers a set of standard HTTP metrics.
// The namespace parameter is typically the service name (e.g. "dispatch_optimizer").
func NewHTTPMetrics(namespace string) *HTTPMetrics {
	m := &HTTPMetrics{
		RequestCount: prometheus.NewCounterVec(
			prometheus.CounterOpts{
				Namespace: namespace,
				Name:      "http_requests_total",
				Help:      "Total number of HTTP requests.",
			},
			[]string{"method", "path", "status"},
		),
		RequestDuration: prometheus.NewHistogramVec(
			prometheus.HistogramOpts{
				Namespace: namespace,
				Name:      "http_request_duration_seconds",
				Help:      "HTTP request latency in seconds.",
				Buckets:   []float64{0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10},
			},
			[]string{"method", "path", "status"},
		),
		RequestSize: prometheus.NewHistogramVec(
			prometheus.HistogramOpts{
				Namespace: namespace,
				Name:      "http_request_size_bytes",
				Help:      "HTTP request body size in bytes.",
				Buckets:   prometheus.ExponentialBuckets(64, 4, 8), // 64B … ~1MB
			},
			[]string{"method", "path"},
		),
		ResponseSize: prometheus.NewHistogramVec(
			prometheus.HistogramOpts{
				Namespace: namespace,
				Name:      "http_response_size_bytes",
				Help:      "HTTP response body size in bytes.",
				Buckets:   prometheus.ExponentialBuckets(64, 4, 8),
			},
			[]string{"method", "path"},
		),
	}

	prometheus.MustRegister(m.RequestCount, m.RequestDuration, m.RequestSize, m.ResponseSize)
	return m
}

// responseWriter wraps http.ResponseWriter to capture status code and bytes written.
type responseWriter struct {
	http.ResponseWriter
	statusCode   int
	bytesWritten int64
}

func newResponseWriter(w http.ResponseWriter) *responseWriter {
	return &responseWriter{ResponseWriter: w, statusCode: http.StatusOK}
}

func (rw *responseWriter) WriteHeader(code int) {
	rw.statusCode = code
	rw.ResponseWriter.WriteHeader(code)
}

func (rw *responseWriter) Write(b []byte) (int, error) {
	n, err := rw.ResponseWriter.Write(b)
	rw.bytesWritten += int64(n)
	return n, err
}

// InstrumentHandler returns an http.Handler that records request count,
// duration, request size, and response size for the given handler name.
func (m *HTTPMetrics) InstrumentHandler(name string, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()

		m.RequestSize.WithLabelValues(r.Method, name).Observe(float64(r.ContentLength))

		rw := newResponseWriter(w)
		next.ServeHTTP(rw, r)

		status := strconv.Itoa(rw.statusCode)
		duration := time.Since(start).Seconds()

		m.RequestCount.WithLabelValues(r.Method, name, status).Inc()
		m.RequestDuration.WithLabelValues(r.Method, name, status).Observe(duration)
		m.ResponseSize.WithLabelValues(r.Method, name).Observe(float64(rw.bytesWritten))
	})
}
