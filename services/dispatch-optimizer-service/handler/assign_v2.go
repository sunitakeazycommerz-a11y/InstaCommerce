// Package handler provides HTTP handlers for the dispatch optimizer service.
package handler

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"time"

	"github.com/instacommerce/dispatch-optimizer-service/optimizer"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
)

const (
	maxBodyBytes = 1 << 20 // 1 MiB
	serviceName  = "dispatch-optimizer-service"
)

// V2AssignRequest is the JSON payload for POST /v2/optimize/assign.
type V2AssignRequest struct {
	// Riders is the set of riders eligible for assignment.
	Riders []optimizer.RiderState `json:"riders"`
	// Orders is the set of pending delivery orders.
	Orders []optimizer.OrderRequest `json:"orders"`
	// Config optionally overrides the solver's default configuration.
	Config *optimizer.SolverConfig `json:"config,omitempty"`
}

// v2ErrorResponse is returned for client and server errors.
type v2ErrorResponse struct {
	Error string `json:"error"`
}

// HandleAssignV2 returns an [http.HandlerFunc] that performs multi-objective
// dispatch optimisation via the supplied solver. The handler:
//
//   - Accepts only POST requests
//   - Validates the JSON request body
//   - Propagates the request context (and therefore any deadline / tracing)
//   - Returns structured JSON responses with appropriate status codes
//   - Records OTel spans for the optimisation phase
//
// If the V2AssignRequest includes a non-nil Config, a temporary solver with
// those overrides is used for the call.
func HandleAssignV2(solver *optimizer.Solver, logger *slog.Logger) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			w.Header().Set("Allow", "POST")
			writeV2Error(w, http.StatusMethodNotAllowed, "method not allowed")
			return
		}

		r.Body = http.MaxBytesReader(w, r.Body, maxBodyBytes)
		decoder := json.NewDecoder(r.Body)
		decoder.DisallowUnknownFields()

		var req V2AssignRequest
		if err := decoder.Decode(&req); err != nil {
			logger.Warn("v2: invalid request body", "error", err)
			writeV2Error(w, http.StatusBadRequest, "invalid request body")
			return
		}
		// Ensure body only contains a single JSON object.
		if err := decoder.Decode(&struct{}{}); err != io.EOF {
			writeV2Error(w, http.StatusBadRequest, "request body must contain a single JSON object")
			return
		}

		if err := validateV2Request(req); err != nil {
			logger.Warn("v2: validation failed", "error", err)
			writeV2Error(w, http.StatusBadRequest, err.Error())
			return
		}

		// Determine which solver to use.
		activeSolver := solver
		if req.Config != nil {
			activeSolver = optimizer.NewSolver(*req.Config)
		}

		// Start OTel span.
		tracer := otel.Tracer(serviceName)
		ctx, span := tracer.Start(r.Context(), "v2.optimize.assign")
		defer span.End()
		span.SetAttributes(
			attribute.Int("riders", len(req.Riders)),
			attribute.Int("orders", len(req.Orders)),
		)

		logger.Info("v2: starting optimisation",
			"riders", len(req.Riders),
			"orders", len(req.Orders),
		)

		result, err := activeSolver.Solve(ctx, req.Riders, req.Orders)
		if err != nil {
			logger.Error("v2: solver error", "error", err)
			writeV2Error(w, http.StatusInternalServerError, "solver failed")
			return
		}

		assignedCount := 0
		for _, a := range result.Assignments {
			assignedCount += len(a.OrderIDs)
		}
		span.SetAttributes(
			attribute.Int("assigned_orders", assignedCount),
			attribute.Int("unassigned_orders", len(result.UnassignedOrders)),
			attribute.Float64("total_cost", result.TotalCost),
			attribute.Int64("solve_duration_ms", result.SolveDurationMs),
		)
		logger.Info("v2: optimisation complete",
			"assigned", assignedCount,
			"unassigned", len(result.UnassignedOrders),
			"solve_ms", result.SolveDurationMs,
		)

		writeV2JSON(w, http.StatusOK, result)
	}
}

// validateV2Request checks the incoming request for obvious issues.
func validateV2Request(req V2AssignRequest) error {
	if len(req.Riders) == 0 && len(req.Orders) == 0 {
		return errors.New("riders and orders must not both be empty")
	}

	riderIDs := make(map[string]struct{}, len(req.Riders))
	for _, rider := range req.Riders {
		if rider.ID == "" {
			return errors.New("rider id is required")
		}
		if _, dup := riderIDs[rider.ID]; dup {
			return errors.New("rider ids must be unique")
		}
		if err := validatePos(rider.Position); err != nil {
			return fmt.Errorf("rider %s: %w", rider.ID, err)
		}
		riderIDs[rider.ID] = struct{}{}
	}

	orderIDs := make(map[string]struct{}, len(req.Orders))
	for _, order := range req.Orders {
		if order.ID == "" {
			return errors.New("order id is required")
		}
		if _, dup := orderIDs[order.ID]; dup {
			return errors.New("order ids must be unique")
		}
		if err := validatePos(order.Position); err != nil {
			return fmt.Errorf("order %s: %w", order.ID, err)
		}
		// Provide a default SLA deadline if missing.
		if order.SLADeadline.IsZero() && !order.CreatedAt.IsZero() {
			// This is informational; the solver handles zero deadlines internally.
			_ = order.CreatedAt.Add(10 * time.Minute)
		}
		orderIDs[order.ID] = struct{}{}
	}

	return nil
}

// validatePos validates a geographic coordinate.
func validatePos(p optimizer.Position) error {
	if p.Lat < -90 || p.Lat > 90 {
		return errors.New("lat must be between -90 and 90")
	}
	if p.Lng < -180 || p.Lng > 180 {
		return errors.New("lng must be between -180 and 180")
	}
	return nil
}

// writeV2JSON writes a JSON payload with the given status code.
func writeV2JSON(w http.ResponseWriter, status int, payload any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	enc := json.NewEncoder(w)
	enc.SetEscapeHTML(false)
	_ = enc.Encode(payload)
}

// writeV2Error writes a JSON error response.
func writeV2Error(w http.ResponseWriter, status int, message string) {
	writeV2JSON(w, status, v2ErrorResponse{Error: message})
}
