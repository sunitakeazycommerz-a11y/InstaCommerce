// Package health provides standard HTTP health-check handlers for
// InstaCommerce Go services. It supports liveness, readiness, and deep health
// checks with pluggable dependency verifiers (databases, caches, brokers, etc.).
package health

import (
	"context"
	"encoding/json"
	"net/http"
	"sync"
	"sync/atomic"
	"time"
)

// Check represents a single named dependency health check.
type Check struct {
	// Name identifies the dependency (e.g. "postgres", "redis", "kafka").
	Name string
	// Check returns nil when the dependency is healthy.
	Check func(ctx context.Context) error
}

// checkResult holds the outcome of a single dependency check.
type checkResult struct {
	Name    string `json:"name"`
	Status  string `json:"status"`
	Error   string `json:"error,omitempty"`
	Latency string `json:"latency"`
}

// healthResponse is serialised as the JSON body of health endpoints.
type healthResponse struct {
	Status  string        `json:"status"`
	Checks  []checkResult `json:"checks,omitempty"`
}

// Handler exposes /health, /health/ready, and /health/live endpoints.
type Handler struct {
	checks []Check
	ready  atomic.Bool
}

// NewHandler creates a Handler with the supplied dependency checks.
// The handler starts in a not-ready state; call SetReady(true) once the
// service has completed initialisation.
func NewHandler(checks ...Check) *Handler {
	return &Handler{checks: checks}
}

// SetReady toggles the readiness state. Kubernetes readiness probes rely on
// this to decide whether to route traffic to the pod.
func (h *Handler) SetReady(ready bool) {
	h.ready.Store(ready)
}

// HealthHandler performs all registered dependency checks and returns
// HTTP 200 if every check passes, or HTTP 503 if any check fails.
func (h *Handler) HealthHandler() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
		defer cancel()

		results := h.runChecks(ctx)

		resp := healthResponse{Status: "ok", Checks: results}
		statusCode := http.StatusOK
		for _, res := range results {
			if res.Status == "fail" {
				resp.Status = "degraded"
				statusCode = http.StatusServiceUnavailable
				break
			}
		}

		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(statusCode)
		_ = json.NewEncoder(w).Encode(resp)
	}
}

// ReadyHandler returns HTTP 200 when the service is ready to accept traffic,
// or HTTP 503 otherwise. It does not run dependency checks.
func (h *Handler) ReadyHandler() http.HandlerFunc {
	return func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if h.ready.Load() {
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(`{"status":"ready"}`))
		} else {
			w.WriteHeader(http.StatusServiceUnavailable)
			_, _ = w.Write([]byte(`{"status":"not_ready"}`))
		}
	}
}

// LiveHandler returns HTTP 200 unconditionally, indicating that the process
// is alive. Kubernetes liveness probes use this to detect hung processes.
func (h *Handler) LiveHandler() http.HandlerFunc {
	return func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"status":"alive"}`))
	}
}

// RegisterRoutes adds /health, /health/ready, and /health/live to the
// supplied ServeMux.
func (h *Handler) RegisterRoutes(mux *http.ServeMux) {
	mux.HandleFunc("/health", h.HealthHandler())
	mux.HandleFunc("/health/ready", h.ReadyHandler())
	mux.HandleFunc("/health/live", h.LiveHandler())
}

// runChecks executes all dependency checks concurrently and collects results.
func (h *Handler) runChecks(ctx context.Context) []checkResult {
	if len(h.checks) == 0 {
		return nil
	}

	results := make([]checkResult, len(h.checks))
	var wg sync.WaitGroup

	for i, c := range h.checks {
		wg.Add(1)
		go func(idx int, chk Check) {
			defer wg.Done()
			start := time.Now()
			err := chk.Check(ctx)
			latency := time.Since(start)

			res := checkResult{
				Name:    chk.Name,
				Status:  "ok",
				Latency: latency.String(),
			}
			if err != nil {
				res.Status = "fail"
				res.Error = err.Error()
			}
			results[idx] = res
		}(i, c)
	}

	wg.Wait()
	return results
}
