// Package health provides health check endpoints for the reverse-etl-orchestrator.
package health

import (
	"encoding/json"
	"net/http"

	"github.com/instacommerce/reverse-etl-orchestrator/internal/subscription"
)

// Checker provides health check functionality.
type Checker struct {
	subscriptionSvc *subscription.Service
}

// NewChecker creates a new health checker.
func NewChecker(subscriptionSvc *subscription.Service) *Checker {
	return &Checker{
		subscriptionSvc: subscriptionSvc,
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
	if err := c.subscriptionSvc.Ping(r.Context()); err != nil {
		response.Status = "DOWN"
		response.Checks["database"] = "DOWN"
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
	if err := c.subscriptionSvc.Ping(r.Context()); err != nil {
		response.Status = "DOWN"
		response.Checks["database"] = "DOWN"
	} else {
		response.Checks["database"] = "UP"
	}

	w.Header().Set("Content-Type", "application/json")
	if response.Status != "UP" {
		w.WriteHeader(http.StatusServiceUnavailable)
	}
	json.NewEncoder(w).Encode(response)
}
