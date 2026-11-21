// Package handler provides HTTP handlers for the dispatch optimizer service.
package handler

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"

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
	if logger == nil {
		logger = slog.Default()
	}
	return func(w http.ResponseWriter, r *http.Request) {
		var req V2AssignRequest
		if !DecodePostJSON(w, r, maxBodyBytes, logger, &req) {
			return
		}

		if err := validateV2Request(req); err != nil {
			logger.Warn("v2: validation failed", "error", err)
			WriteError(w, http.StatusBadRequest, err.Error())
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
			WriteError(w, http.StatusInternalServerError, "solver failed")
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

		WriteJSON(w, http.StatusOK, result)
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
		if err := optimizer.ValidatePosition(rider.Position); err != nil {
			return fmt.Errorf("rider %s: %w", rider.ID, err)
		}
		if err := validateNonNegativeFinite("current_bag_weight_kg", rider.CurrentBagWeightKg); err != nil {
			return fmt.Errorf("rider %s: %w", rider.ID, err)
		}
		if err := validateNonNegativeFinite("current_bag_volume_liters", rider.CurrentBagVolumeLiters); err != nil {
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
		if err := optimizer.ValidatePosition(order.Position); err != nil {
			return fmt.Errorf("order %s: %w", order.ID, err)
		}
		if order.StorePosition != nil {
			if err := optimizer.ValidatePosition(*order.StorePosition); err != nil {
				return fmt.Errorf("order %s store_position: %w", order.ID, err)
			}
		}
		if err := validateNonNegativeFinite("weight", order.Weight); err != nil {
			return fmt.Errorf("order %s: %w", order.ID, err)
		}
		if err := validateNonNegativeFinite("volume_liters", order.VolumeLiters); err != nil {
			return fmt.Errorf("order %s: %w", order.ID, err)
		}
		orderIDs[order.ID] = struct{}{}
	}

	return nil
}

// DecodePostJSON validates POST method and decodes a single strict JSON object up to maxBytes.
func DecodePostJSON(w http.ResponseWriter, r *http.Request, maxBytes int64, logger *slog.Logger, dst any) bool {
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", "POST")
		WriteError(w, http.StatusMethodNotAllowed, "method not allowed")
		return false
	}

	r.Body = http.MaxBytesReader(w, r.Body, maxBytes)
	decoder := json.NewDecoder(r.Body)
	decoder.DisallowUnknownFields()

	if err := decoder.Decode(dst); err != nil {
		if logger != nil {
			logger.Warn("invalid request body", "error", err)
		}
		WriteError(w, http.StatusBadRequest, "invalid request body")
		return false
	}
	if err := decoder.Decode(&struct{}{}); err != io.EOF {
		if logger != nil {
			logger.Warn("invalid request body", "error", err)
		}
		WriteError(w, http.StatusBadRequest, "request body must contain a single JSON object")
		return false
	}
	return true
}

// WriteJSON writes a JSON payload with the given status code.
func WriteJSON(w http.ResponseWriter, status int, payload any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	enc := json.NewEncoder(w)
	enc.SetEscapeHTML(false)
	_ = enc.Encode(payload)
}

// WriteError writes a JSON error response.
func WriteError(w http.ResponseWriter, status int, message string) {
	WriteJSON(w, status, v2ErrorResponse{Error: message})
}
