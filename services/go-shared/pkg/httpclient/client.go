// Package httpclient provides a production HTTP client with automatic retries,
// exponential backoff, and a circuit breaker for InstaCommerce Go services.
package httpclient

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"math"
	"math/rand/v2"
	"net"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/instacommerce/go-shared/pkg/auth"
	"github.com/instacommerce/go-shared/pkg/correlation"
	"go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/propagation"
)

const maxRetryAfterDelay = 30 * time.Second

// Client is a production HTTP client with retry, circuit breaker, and timeouts.
type Client struct {
	httpClient          *http.Client
	baseTransport       *http.Transport
	circuitBreaker      *CircuitBreaker
	dnsCache            *DNSCache
	maxRetries          int
	baseDelay           time.Duration
	internalServiceName string
	internalToken       string
	logger              *slog.Logger
}

// DNSCache returns the client's DNSCache instance.
func (c *Client) DNSCache() *DNSCache {
	return c.dnsCache
}

// Option configures a Client.
type Option func(*Client)

// WithTimeout sets the underlying HTTP client timeout.
func WithTimeout(d time.Duration) Option {
	return func(c *Client) {
		if d > 0 {
			c.httpClient.Timeout = d
		}
	}
}

// WithMaxRetries sets the maximum number of retry attempts.
func WithMaxRetries(n int) Option {
	return func(c *Client) {
		if n < 0 {
			n = 0
		}
		c.maxRetries = n
	}
}

// WithBaseDelay sets the initial backoff delay between retries.
func WithBaseDelay(d time.Duration) Option {
	return func(c *Client) {
		if d >= 0 {
			c.baseDelay = d
		}
	}
}

// WithCircuitBreakerThreshold sets the number of consecutive failures before
// the circuit opens.
func WithCircuitBreakerThreshold(n int) Option {
	return func(c *Client) {
		if n >= 1 {
			c.circuitBreaker.threshold = n
		}
	}
}

// WithCircuitBreakerResetTimeout sets how long the circuit stays open before
// transitioning to half-open.
func WithCircuitBreakerResetTimeout(d time.Duration) Option {
	return func(c *Client) {
		if d > 0 {
			c.circuitBreaker.resetTimeout = d
		}
	}
}

// WithLogger sets a custom logger.
func WithLogger(l *slog.Logger) Option {
	return func(c *Client) {
		if l != nil {
			c.logger = l
		}
	}
}

// WithDNSCacheTTL sets the TTL for DNS resolution caching.
func WithDNSCacheTTL(d time.Duration) Option {
	return func(c *Client) {
		c.dnsCache.ttl = d
	}
}

// WithIdleConnTimeout sets the maximum idle connection timeout for connection pooling.
func WithIdleConnTimeout(d time.Duration) Option {
	return func(c *Client) {
		if c.baseTransport != nil && d >= 0 {
			c.baseTransport.IdleConnTimeout = d
		}
	}
}

// WithInternalAuth configures X-Internal-Service and X-Internal-Token headers
// on outbound requests when not already specified by the caller.
func WithInternalAuth(serviceName, token string) Option {
	return func(c *Client) {
		c.internalServiceName = strings.TrimSpace(serviceName)
		c.internalToken = token
	}
}

// NewClient creates a Client with production defaults.
func NewClient(opts ...Option) *Client {
	dnsCache := NewDNSCache(5 * time.Second)
	dialer := &net.Dialer{
		Timeout:   10 * time.Second,
		KeepAlive: 30 * time.Second,
		Resolver:  &net.Resolver{PreferGo: true},
	}
	baseTransport := &http.Transport{
		Proxy:                 http.ProxyFromEnvironment,
		DialContext:           dnsCache.DialContext(dialer),
		ForceAttemptHTTP2:     true,
		MaxIdleConns:          100,
		MaxIdleConnsPerHost:   100,
		IdleConnTimeout:       30 * time.Second,
		TLSHandshakeTimeout:   10 * time.Second,
		ExpectContinueTimeout: 1 * time.Second,
	}
	c := &Client{
		httpClient: &http.Client{
			Timeout:   30 * time.Second,
			Transport: otelhttp.NewTransport(baseTransport),
		},
		baseTransport:  baseTransport,
		circuitBreaker: newCircuitBreaker(5, 30*time.Second),
		dnsCache:       dnsCache,
		maxRetries:     3,
		baseDelay:      500 * time.Millisecond,
		logger:         slog.Default(),
	}
	for _, o := range opts {
		if o != nil {
			o(c)
		}
	}
	return c
}

// Do executes req with retries on transient failures (network errors, 429, 5xx)
// and updates the circuit breaker once per logical request outcome.
func (c *Client) Do(ctx context.Context, req *http.Request) (*http.Response, error) {
	if req == nil {
		return nil, fmt.Errorf("httpclient: request must not be nil")
	}
	if err := ctx.Err(); err != nil {
		return nil, fmt.Errorf("httpclient: context done before request: %w", err)
	}
	if !c.circuitBreaker.allow() {
		return nil, fmt.Errorf("httpclient: circuit breaker is open for %s %s", req.Method, req.URL)
	}

	probeSettled := false
	defer func() {
		if !probeSettled {
			c.circuitBreaker.abortProbe()
		}
	}()

	var body []byte
	if req.Body != nil {
		var err error
		body, err = io.ReadAll(req.Body)
		_ = req.Body.Close()
		if err != nil {
			return nil, fmt.Errorf("httpclient: reading request body: %w", err)
		}
	}

	outReq := req.Clone(ctx)
	c.injectHeaders(ctx, outReq)

	var lastErr error
	var nextDelay time.Duration
	attempts := 0
	for attempt := 0; attempt <= c.maxRetries; attempt++ {
		if attempt > 0 {
			delay := nextDelay
			if delay <= 0 {
				delay = c.backoff(attempt)
			}
			c.logger.Debug("retrying request",
				slog.String("method", outReq.Method),
				slog.String("url", outReq.URL.String()),
				slog.Int("attempt", attempt),
				slog.Duration("delay", delay),
			)
			if err := waitWithContext(ctx, delay); err != nil {
				if errors.Is(err, context.DeadlineExceeded) {
					if lastErr == nil {
						lastErr = err
					}
					break
				}
				return nil, fmt.Errorf("httpclient: context cancelled during retry: %w", err)
			}
		}

		attempts = attempt + 1
		if body != nil {
			outReq.Body = io.NopCloser(bytes.NewReader(body))
			outReq.ContentLength = int64(len(body))
			outReq.GetBody = func() (io.ReadCloser, error) {
				return io.NopCloser(bytes.NewReader(body)), nil
			}
		}

		resp, err := c.httpClient.Do(outReq)
		if err != nil {
			if errors.Is(ctx.Err(), context.Canceled) {
				return nil, fmt.Errorf("httpclient: %s %s cancelled: %w", outReq.Method, outReq.URL, err)
			}
			lastErr = err
			nextDelay = 0
			c.logger.Warn("request failed",
				slog.String("method", outReq.Method),
				slog.String("url", outReq.URL.String()),
				slog.Int("attempt", attempt),
				slog.String("error", err.Error()),
			)
			if errors.Is(ctx.Err(), context.DeadlineExceeded) {
				break
			}
			continue
		}

		if isRetryableStatus(resp.StatusCode) {
			nextDelay = parseRetryAfter(resp.Header.Get("Retry-After"))
			c.logger.Warn("retryable HTTP status",
				slog.String("method", outReq.Method),
				slog.String("url", outReq.URL.String()),
				slog.Int("attempt", attempt),
				slog.Int("status", resp.StatusCode),
			)
			_, _ = io.Copy(io.Discard, resp.Body)
			_ = resp.Body.Close()
			lastErr = fmt.Errorf("httpclient: server returned %d", resp.StatusCode)
			if errors.Is(ctx.Err(), context.DeadlineExceeded) {
				break
			}
			continue
		}

		c.circuitBreaker.recordSuccess()
		probeSettled = true
		return resp, nil
	}

	c.circuitBreaker.recordFailure()
	probeSettled = true
	return nil, fmt.Errorf("httpclient: %s %s failed after %d attempts: %w",
		outReq.Method, outReq.URL, attempts, lastErr)
}

func (c *Client) injectHeaders(ctx context.Context, req *http.Request) {
	if req.Header == nil {
		req.Header = make(http.Header)
	}
	prop := otel.GetTextMapPropagator()
	prop.Inject(ctx, propagation.HeaderCarrier(req.Header))
	if req.Header.Get("traceparent") == "" {
		propagation.TraceContext{}.Inject(ctx, propagation.HeaderCarrier(req.Header))
	}
	if req.Header.Get("baggage") == "" {
		propagation.Baggage{}.Inject(ctx, propagation.HeaderCarrier(req.Header))
	}
	if cid := correlation.FromContext(ctx); cid != "" && req.Header.Get(correlation.Header) == "" {
		req.Header.Set(correlation.Header, cid)
	}
	if c.internalServiceName != "" && c.internalToken != "" {
		if req.Header.Get(auth.HeaderService) == "" {
			req.Header.Set(auth.HeaderService, c.internalServiceName)
		}
		if req.Header.Get(auth.HeaderToken) == "" {
			req.Header.Set(auth.HeaderToken, c.internalToken)
		}
	}
}

func isRetryableStatus(status int) bool {
	return status == http.StatusTooManyRequests || status >= http.StatusInternalServerError
}

func parseRetryAfter(raw string) time.Duration {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return 0
	}
	if secs, err := strconv.Atoi(raw); err == nil && secs > 0 {
		d := time.Duration(secs) * time.Second
		if d > maxRetryAfterDelay {
			return maxRetryAfterDelay
		}
		return d
	}
	return 0
}

func waitWithContext(ctx context.Context, d time.Duration) error {
	if d <= 0 {
		return ctx.Err()
	}
	timer := time.NewTimer(d)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-timer.C:
		return nil
	}
}

// Get is a convenience wrapper around Do for GET requests.
func (c *Client) Get(ctx context.Context, url string) (*http.Response, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return nil, fmt.Errorf("httpclient: creating GET request: %w", err)
	}
	return c.Do(ctx, req)
}

// PostJSON marshals body as JSON and sends a POST request.
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

func (c *Client) backoff(attempt int) time.Duration {
	d := float64(c.baseDelay) * math.Pow(2, float64(attempt-1))
	jitter := d * 0.2 * rand.Float64()
	return time.Duration(d + jitter)
}
