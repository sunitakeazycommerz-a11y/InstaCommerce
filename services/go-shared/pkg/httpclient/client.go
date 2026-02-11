// Package httpclient provides a production HTTP client with automatic retries,
// exponential backoff, and a circuit breaker for InstaCommerce Go services.
//
// Use this client for all outbound HTTP calls to upstream services so that
// transient failures are handled consistently across the platform.
package httpclient

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"math"
	"math/rand/v2"
	"net/http"
	"sync"
	"time"
)

// CircuitState represents the state of the circuit breaker.
type CircuitState int

const (
	// Closed means the circuit is healthy and requests flow through normally.
	Closed CircuitState = iota
	// Open means the circuit has tripped; requests are rejected immediately.
	Open
	// HalfOpen means the circuit is testing whether the upstream has recovered.
	HalfOpen
)

// String returns a human-readable circuit state name.
func (s CircuitState) String() string {
	switch s {
	case Closed:
		return "closed"
	case Open:
		return "open"
	case HalfOpen:
		return "half-open"
	default:
		return "unknown"
	}
}

// CircuitBreaker implements a simple state-machine circuit breaker.
// When consecutive failures reach the threshold the circuit opens, rejecting
// all requests for resetTimeout. After the timeout one probe request is
// allowed through (half-open); if it succeeds the circuit closes, otherwise
// it reopens.
type CircuitBreaker struct {
	failures     int
	threshold    int
	resetTimeout time.Duration
	state        CircuitState
	lastFailure  time.Time
	mu           sync.Mutex
}

// newCircuitBreaker creates a CircuitBreaker with the given threshold and
// reset timeout.
func newCircuitBreaker(threshold int, resetTimeout time.Duration) *CircuitBreaker {
	return &CircuitBreaker{
		threshold:    threshold,
		resetTimeout: resetTimeout,
		state:        Closed,
	}
}

// allow reports whether a request should be attempted. It transitions from
// Open to HalfOpen when the reset timeout has elapsed.
func (cb *CircuitBreaker) allow() bool {
	cb.mu.Lock()
	defer cb.mu.Unlock()

	switch cb.state {
	case Closed:
		return true
	case Open:
		if time.Since(cb.lastFailure) > cb.resetTimeout {
			cb.state = HalfOpen
			return true
		}
		return false
	case HalfOpen:
		return true
	default:
		return true
	}
}

// recordSuccess resets the failure counter and closes the circuit.
func (cb *CircuitBreaker) recordSuccess() {
	cb.mu.Lock()
	defer cb.mu.Unlock()
	cb.failures = 0
	cb.state = Closed
}

// recordFailure increments the failure counter and opens the circuit when the
// threshold is reached.
func (cb *CircuitBreaker) recordFailure() {
	cb.mu.Lock()
	defer cb.mu.Unlock()
	cb.failures++
	cb.lastFailure = time.Now()
	if cb.failures >= cb.threshold {
		cb.state = Open
	}
}

// Client is a production HTTP client with retry, circuit breaker, and timeouts.
type Client struct {
	httpClient     *http.Client
	circuitBreaker *CircuitBreaker
	maxRetries     int
	baseDelay      time.Duration
	logger         *slog.Logger
}

// Option configures a Client.
type Option func(*Client)

// WithTimeout sets the underlying HTTP client timeout.
func WithTimeout(d time.Duration) Option {
	return func(c *Client) {
		c.httpClient.Timeout = d
	}
}

// WithMaxRetries sets the maximum number of retry attempts.
func WithMaxRetries(n int) Option {
	return func(c *Client) {
		c.maxRetries = n
	}
}

// WithBaseDelay sets the initial backoff delay between retries.
func WithBaseDelay(d time.Duration) Option {
	return func(c *Client) {
		c.baseDelay = d
	}
}

// WithCircuitBreakerThreshold sets the number of consecutive failures before
// the circuit opens.
func WithCircuitBreakerThreshold(n int) Option {
	return func(c *Client) {
		c.circuitBreaker.threshold = n
	}
}

// WithCircuitBreakerResetTimeout sets how long the circuit stays open before
// transitioning to half-open.
func WithCircuitBreakerResetTimeout(d time.Duration) Option {
	return func(c *Client) {
		c.circuitBreaker.resetTimeout = d
	}
}

// WithLogger sets a custom logger.
func WithLogger(l *slog.Logger) Option {
	return func(c *Client) {
		c.logger = l
	}
}

// NewClient creates a Client with production defaults. Use Option functions
// to override timeout, retry, and circuit breaker settings.
//
// Defaults:
//   - Timeout: 30s
//   - MaxRetries: 3
//   - BaseDelay: 500ms (exponential backoff with jitter)
//   - CircuitBreaker threshold: 5 consecutive failures
//   - CircuitBreaker reset timeout: 30s
func NewClient(opts ...Option) *Client {
	c := &Client{
		httpClient:     &http.Client{Timeout: 30 * time.Second},
		circuitBreaker: newCircuitBreaker(5, 30*time.Second),
		maxRetries:     3,
		baseDelay:      500 * time.Millisecond,
		logger:         slog.Default(),
	}
	for _, o := range opts {
		o(c)
	}
	return c
}

// Do executes the given request with retry and circuit breaker logic.
// Retries are attempted only for 5xx responses and network errors.
// The request body is consumed on each attempt; callers should use
// http.NewRequestWithContext with a re-readable body if needed.
func (c *Client) Do(ctx context.Context, req *http.Request) (*http.Response, error) {
	if !c.circuitBreaker.allow() {
		return nil, fmt.Errorf("httpclient: circuit breaker is open for %s %s", req.Method, req.URL)
	}

	var (
		resp    *http.Response
		lastErr error
		body    []byte
	)

	// Buffer the body so it can be replayed on retries.
	if req.Body != nil {
		var err error
		body, err = io.ReadAll(req.Body)
		if err != nil {
			return nil, fmt.Errorf("httpclient: reading request body: %w", err)
		}
		req.Body.Close()
	}

	for attempt := 0; attempt <= c.maxRetries; attempt++ {
		if attempt > 0 {
			delay := c.backoff(attempt)
			c.logger.Debug("retrying request",
				slog.String("method", req.Method),
				slog.String("url", req.URL.String()),
				slog.Int("attempt", attempt),
				slog.Duration("delay", delay),
			)

			select {
			case <-ctx.Done():
				return nil, fmt.Errorf("httpclient: context cancelled during retry: %w", ctx.Err())
			case <-time.After(delay):
			}
		}

		// Reset the body for this attempt.
		if body != nil {
			req.Body = io.NopCloser(bytes.NewReader(body))
		}

		resp, lastErr = c.httpClient.Do(req.WithContext(ctx))
		if lastErr != nil {
			c.circuitBreaker.recordFailure()
			c.logger.Warn("request failed",
				slog.String("method", req.Method),
				slog.String("url", req.URL.String()),
				slog.Int("attempt", attempt),
				slog.String("error", lastErr.Error()),
			)
			continue
		}

		// Only retry on server errors (5xx).
		if resp.StatusCode >= 500 {
			c.circuitBreaker.recordFailure()
			c.logger.Warn("server error",
				slog.String("method", req.Method),
				slog.String("url", req.URL.String()),
				slog.Int("attempt", attempt),
				slog.Int("status", resp.StatusCode),
			)
			// Drain and close body to allow connection reuse.
			_, _ = io.Copy(io.Discard, resp.Body)
			resp.Body.Close()
			lastErr = fmt.Errorf("httpclient: server returned %d", resp.StatusCode)
			continue
		}

		c.circuitBreaker.recordSuccess()
		return resp, nil
	}

	return nil, fmt.Errorf("httpclient: %s %s failed after %d attempts: %w",
		req.Method, req.URL, c.maxRetries+1, lastErr)
}

// Get is a convenience wrapper around Do for GET requests.
func (c *Client) Get(ctx context.Context, url string) (*http.Response, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return nil, fmt.Errorf("httpclient: creating GET request: %w", err)
	}
	return c.Do(ctx, req)
}

// PostJSON is a convenience wrapper that marshals body as JSON and sends a
// POST request with Content-Type application/json.
func (c *Client) PostJSON(ctx context.Context, url string, body interface{}) (*http.Response, error) {
	data, err := json.Marshal(body)
	if err != nil {
		return nil, fmt.Errorf("httpclient: marshalling request body: %w", err)
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(data))
	if err != nil {
		return nil, fmt.Errorf("httpclient: creating POST request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	return c.Do(ctx, req)
}

// backoff calculates an exponential backoff duration with jitter.
func (c *Client) backoff(attempt int) time.Duration {
	d := float64(c.baseDelay) * math.Pow(2, float64(attempt-1))
	jitter := d * 0.2 * rand.Float64()
	return time.Duration(d + jitter)
}
