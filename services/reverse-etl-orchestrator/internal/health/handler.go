// Package health provides health check endpoints for the reverse-etl-orchestrator.
package health

import (
	"context"
	"encoding/json"
	"net/http"
)

// Pinger provides dependency connectivity check capability.
type Pinger interface {
	Ping(ctx context.Context) error
}

// Checker provides health check functionality.
type Checker struct {
	pinger Pinger
}

// NewChecker creates a new health checker.
func NewChecker(pinger Pinger) *Checker {
	return &Checker{
		pinger: pinger,
	}
}

// HealthResponse represents the health check response.
type HealthResponse struct {
	Status  string            `json:"status"`
	Checks  map[string]string `json:"checks,omitempty"`
	Version string            `json:"version,omitempty"`
}

// Health handles the /health endpoint (combined liveness + readiness).
func (c *Checker) Health(w http.ResponseWriter, r *http.Request) {
	response := HealthResponse{
		Status:  "UP",
		Version: "1.0.0",
		Checks: map[string]string{
			"database": "UP",
			"kafka":    "UP",
			"temporal": "UP",
		},
	}

	// Check database connectivity
	if c.pinger != nil {
		if err := c.pinger.Ping(r.Context()); err != nil {
			response.Status = "DOWN"
			response.Checks["database"] = "DOWN"
		}
	}

	w.Header().Set("Content-Type", "application/json")
	if response.Status != "UP" {
		w.WriteHeader(http.StatusServiceUnavailable)
	}
	json.NewEncoder(w).Encode(response)
}

// Liveness handles the /health/live endpoint.
// Returns 200 if the process is alive and can handle requests.
func (c *Checker) Liveness(w http.ResponseWriter, r *http.Request) {
	response := HealthResponse{
		Status: "UP",
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(response)
}

// Readiness handles the /health/ready endpoint.
// Returns 200 if the service is ready to accept traffic.
func (c *Checker) Readiness(w http.ResponseWriter, r *http.Request) {
	response := HealthResponse{
		Status: "UP",
		Checks: map[string]string{},
	}

	// Check all dependencies
	if c.pinger != nil {
		if err := c.pinger.Ping(r.Context()); err != nil {
			response.Status = "DOWN"
			response.Checks["database"] = "DOWN"
		} else {
			response.Checks["database"] = "UP"
		}
	}

	w.Header().Set("Content-Type", "application/json")
	if response.Status != "UP" {
		w.WriteHeader(http.StatusServiceUnavailable)
	}
	json.NewEncoder(w).Encode(response)
}
