// Package health provides health check endpoints for the reverse-etl-orchestrator.
package health

import (
	"context"
	"encoding/json"
	"log/slog"
	"net/http"
	"reflect"
	"sync"
	"time"
)

const (
	// StatusUP indicates a healthy service or dependency.
	StatusUP = "UP"
	// StatusDOWN indicates a failing service or dependency.
	StatusDOWN = "DOWN"
	// StatusUNCONFIGURED indicates an optional dependency has no checker configured.
	StatusUNCONFIGURED = "UNCONFIGURED"

	defaultVersion      = "1.0.0"
	defaultCheckTimeout = 5 * time.Second
)

// Pinger provides dependency connectivity check capability.
type Pinger interface {
	Ping(ctx context.Context) error
}

// PingerFunc adapts a function to the Pinger interface.
type PingerFunc func(ctx context.Context) error

// Ping invokes the wrapped check function.
func (f PingerFunc) Ping(ctx context.Context) error {
	return f(ctx)
}

// Option configures optional dependencies and probe settings on Checker.
type Option func(*Checker)

// WithDatabase configures the database dependency checker.
func WithDatabase(p Pinger) Option {
	return WithDependency("database", p)
}

// WithKafka configures the Kafka dependency checker.
func WithKafka(p Pinger) Option {
	return WithDependency("kafka", p)
}

// WithTemporal configures the Temporal dependency checker.
func WithTemporal(p Pinger) Option {
	return WithDependency("temporal", p)
}

// WithRedis configures the Redis dependency checker.
func WithRedis(p Pinger) Option {
	return WithDependency("redis", p)
}

// WithDependency registers or replaces a named dependency checker.
func WithDependency(name string, p Pinger) Option {
	return func(c *Checker) {
		if name == "" {
			return
		}
		if isNilPinger(p) {
			c.deps[name] = nil
			return
		}
		c.deps[name] = p
	}
}

// WithVersion sets the reported service version on /health responses.
func WithVersion(version string) Option {
	return func(c *Checker) {
		if version != "" {
			c.version = version
		}
	}
}

// WithCheckTimeout sets the maximum duration allowed for readiness dependency checks.
func WithCheckTimeout(timeout time.Duration) Option {
	return func(c *Checker) {
		if timeout > 0 {
			c.checkTimeout = timeout
		}
	}
}

// Checker provides liveness, readiness, and deep health check functionality.
type Checker struct {
	deps         map[string]Pinger
	version      string
	checkTimeout time.Duration
}

// NewChecker creates a new health checker with an optional database Pinger and options.
func NewChecker(pinger Pinger, opts ...Option) *Checker {
	c := &Checker{
		deps: map[string]Pinger{
			"database": nil,
			"kafka":    nil,
			"redis":    nil,
			"temporal": nil,
		},
		version:      defaultVersion,
		checkTimeout: defaultCheckTimeout,
	}
	if !isNilPinger(pinger) {
		c.deps["database"] = pinger
	}
	for _, opt := range opts {
		if opt != nil {
			opt(c)
		}
	}
	return c
}

// HealthResponse represents the health check response.
type HealthResponse struct {
	Status  string            `json:"status"`
	Checks  map[string]string `json:"checks,omitempty"`
	Version string            `json:"version,omitempty"`
}

// Health handles the /health endpoint (readiness + version metadata).
func (c *Checker) Health(w http.ResponseWriter, r *http.Request) {
	c.writeDependencyHealth(w, r, true)
}

// Liveness handles the /health/live endpoint.
// Returns 200 UP if the process is running without probing external dependencies.
func (c *Checker) Liveness(w http.ResponseWriter, _ *http.Request) {
	writeHealthJSON(w, http.StatusOK, HealthResponse{
		Status: StatusUP,
	})
}

// Readiness handles the /health/ready endpoint.
// Returns 200 UP if all configured dependencies succeed, or 503 DOWN otherwise.
func (c *Checker) Readiness(w http.ResponseWriter, r *http.Request) {
	c.writeDependencyHealth(w, r, false)
}

func (c *Checker) writeDependencyHealth(w http.ResponseWriter, r *http.Request, includeVersion bool) {
	if c == nil {
		writeHealthJSON(w, http.StatusOK, HealthResponse{Status: StatusUP})
		return
	}

	timeout := c.checkTimeout
	if timeout <= 0 {
		timeout = defaultCheckTimeout
	}
	ctx, cancel := context.WithTimeout(r.Context(), timeout)
	defer cancel()

	checks := make(map[string]string, len(c.deps))
	var mu sync.Mutex
	var wg sync.WaitGroup
	status := StatusUP

	for name, pinger := range c.deps {
		if isNilPinger(pinger) {
			mu.Lock()
			checks[name] = StatusUNCONFIGURED
			mu.Unlock()
			continue
		}
		wg.Add(1)
		go func(depName string, depPinger Pinger) {
			defer wg.Done()
			err := depPinger.Ping(ctx)
			mu.Lock()
			if err != nil {
				checks[depName] = StatusDOWN
				status = StatusDOWN
			} else {
				checks[depName] = StatusUP
			}
			mu.Unlock()
		}(name, pinger)
	}
	wg.Wait()

	resp := HealthResponse{
		Status: status,
		Checks: checks,
	}
	if includeVersion {
		resp.Version = c.version
		if resp.Version == "" {
			resp.Version = defaultVersion
		}
	}

	code := http.StatusOK
	if status != StatusUP {
		code = http.StatusServiceUnavailable
	}
	writeHealthJSON(w, code, resp)
}

func writeHealthJSON(w http.ResponseWriter, statusCode int, resp HealthResponse) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(statusCode)
	if err := json.NewEncoder(w).Encode(resp); err != nil {
		slog.Error("failed to encode health response", "error", err)
	}
}

func isNilPinger(p Pinger) bool {
	if p == nil {
		return true
	}
	v := reflect.ValueOf(p)
	switch v.Kind() {
	case reflect.Ptr, reflect.Interface, reflect.Func, reflect.Map, reflect.Slice, reflect.Chan:
		return v.IsNil()
	default:
		return false
	}
}
