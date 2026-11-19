package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"math"
	"net/http"
	"os"
	"sort"
	"strings"
	"time"

	"github.com/instacommerce/dispatch-optimizer-service/optimizer"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
)

type Position struct {
	Lat float64 `json:"lat"`
	Lng float64 `json:"lng"`
}

type Rider struct {
	ID       string   `json:"id"`
	Position Position `json:"position"`
	Zone     string   `json:"zone,omitempty"`
}

type Order struct {
	ID       string   `json:"id"`
	Position Position `json:"position"`
	Zone     string   `json:"zone,omitempty"`
}

type AssignRequest struct {
	Riders   []Rider `json:"riders"`
	Orders   []Order `json:"orders"`
	Capacity int     `json:"capacity"`
}

type Assignment struct {
	RiderID       string   `json:"rider_id"`
	OrderIDs      []string `json:"order_ids"`
	TotalDistance float64  `json:"total_distance"`
}

type AssignResponse struct {
	Assignments      []Assignment `json:"assignments"`
	UnassignedOrders []string     `json:"unassigned_orders"`
}

type errorResponse struct {
	Error string `json:"error"`
}

type Constraint interface {
	Name() string
	Allows(rider Rider, order Order, assigned []Order) bool
}

type ConstraintSet []Constraint

func (cs ConstraintSet) Allows(rider Rider, order Order, assigned []Order) bool {
	for _, constraint := range cs {
		if !constraint.Allows(rider, order, assigned) {
			return false
		}
	}
	return true
}

func (cs ConstraintSet) Names() []string {
	names := make([]string, 0, len(cs))
	for _, constraint := range cs {
		names = append(names, constraint.Name())
	}
	return names
}

type CapacityConstraint struct {
	Max int
}

func (c CapacityConstraint) Name() string { return "capacity" }

func (c CapacityConstraint) Allows(_ Rider, _ Order, assigned []Order) bool {
	return len(assigned) < c.Max
}

type ZoneConstraint struct{}

func (ZoneConstraint) Name() string { return "zone" }

func (ZoneConstraint) Allows(rider Rider, order Order, _ []Order) bool {
	if rider.Zone == "" || order.Zone == "" {
		return true
	}
	return rider.Zone == order.Zone
}

type constraintFactory func(req AssignRequest) Constraint

var constraintFactories = map[string]constraintFactory{
	"capacity": func(req AssignRequest) Constraint {
		return CapacityConstraint{Max: req.Capacity}
	},
	"zone": func(AssignRequest) Constraint {
		return ZoneConstraint{}
	},
}

func handleAssign(w http.ResponseWriter, r *http.Request, logger *slog.Logger, metrics *serviceMetrics) {
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", "POST")
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}

	r.Body = http.MaxBytesReader(w, r.Body, maxBodyBytes)
	decoder := json.NewDecoder(r.Body)
	decoder.DisallowUnknownFields()

	var req AssignRequest
	if err := decoder.Decode(&req); err != nil {
		logger.Warn("invalid request body", "error", err)
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	if err := decoder.Decode(&struct{}{}); err != io.EOF {
		logger.Warn("invalid request body", "error", err)
		writeError(w, http.StatusBadRequest, "request body must contain a single JSON object")
		return
	}
	if err := validateRequest(req); err != nil {
		logger.Warn("invalid request", "error", err)
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}

	constraints := buildConstraints(req, logger)
	tracer := otel.Tracer(serviceName)
	_, span := tracer.Start(r.Context(), "optimize.assign")
	span.SetAttributes(
		attribute.Int("riders", len(req.Riders)),
		attribute.Int("orders", len(req.Orders)),
		attribute.Int("capacity", req.Capacity),
		attribute.StringSlice("constraints", constraints.Names()),
	)
	start := time.Now()
	assignments, unassigned := optimizeAssignments(req.Riders, req.Orders, constraints)
	metrics.optimizeDuration.Observe(time.Since(start).Seconds())
	assignedCount := 0
	totalDistance := 0.0
	for _, assignment := range assignments {
		assignedCount += len(assignment.OrderIDs)
		totalDistance += assignment.TotalDistance
	}
	metrics.assignedOrders.Add(float64(assignedCount))
	metrics.unassignedOrders.Add(float64(len(unassigned)))
	span.SetAttributes(
		attribute.Int("assigned_orders", assignedCount),
		attribute.Int("unassigned_orders", len(unassigned)),
		attribute.Float64("total_distance_km", totalDistance),
	)
	span.End()
	writeJSON(w, http.StatusOK, AssignResponse{
		Assignments:      assignments,
		UnassignedOrders: unassigned,
	})
}

func validateRequest(req AssignRequest) error {
	if req.Capacity <= 0 {
		return errors.New("capacity must be greater than 0")
	}

	riderIDs := make(map[string]struct{}, len(req.Riders))
	for _, rider := range req.Riders {
		if rider.ID == "" {
			return errors.New("rider id is required")
		}
		if _, exists := riderIDs[rider.ID]; exists {
			return errors.New("rider ids must be unique")
		}
		if err := validatePosition(rider.Position); err != nil {
			return fmt.Errorf("rider %s: %w", rider.ID, err)
		}
		riderIDs[rider.ID] = struct{}{}
	}

	orderIDs := make(map[string]struct{}, len(req.Orders))
	for _, order := range req.Orders {
		if order.ID == "" {
			return errors.New("order id is required")
		}
		if _, exists := orderIDs[order.ID]; exists {
			return errors.New("order ids must be unique")
		}
		if err := validatePosition(order.Position); err != nil {
			return fmt.Errorf("order %s: %w", order.ID, err)
		}
		orderIDs[order.ID] = struct{}{}
	}
	return nil
}

func validatePosition(p Position) error {
	if math.IsNaN(p.Lat) || math.IsInf(p.Lat, 0) || p.Lat < -90 || p.Lat > 90 {
		return errors.New("lat must be a finite number between -90 and 90")
	}
	if math.IsNaN(p.Lng) || math.IsInf(p.Lng, 0) || p.Lng < -180 || p.Lng > 180 {
		return errors.New("lng must be a finite number between -180 and 180")
	}
	return nil
}

func buildConstraints(req AssignRequest, logger *slog.Logger) ConstraintSet {
	selection := strings.TrimSpace(os.Getenv("DISPATCH_CONSTRAINTS"))
	if selection == "" {
		selection = "zone"
	}
	parts := strings.Split(selection, ",")
	constraints := make(ConstraintSet, 0, len(parts)+1)
	seen := map[string]bool{"capacity": true}
	constraints = append(constraints, constraintFactories["capacity"](req))
	for _, part := range parts {
		name := strings.ToLower(strings.TrimSpace(part))
		if name == "" || seen[name] {
			continue
		}
		factory, ok := constraintFactories[name]
		if !ok {
			logger.Warn("unknown constraint", "constraint", name)
			continue
		}
		constraints = append(constraints, factory(req))
		seen[name] = true
	}
	return constraints
}

func optimizeAssignments(riders []Rider, orders []Order, constraints ConstraintSet) ([]Assignment, []string) {
	assigned := make(map[string]bool, len(orders))
	assignments := make([]Assignment, 0, len(riders))

	for _, rider := range riders {
		current := rider.Position
		orderIDs := make([]string, 0)
		assignedOrders := make([]Order, 0)
		totalDistance := 0.0

		for {
			nearestIndex := -1
			nearestDistance := math.MaxFloat64
			nearestID := ""

			for i, order := range orders {
				if assigned[order.ID] || !constraints.Allows(rider, order, assignedOrders) {
					continue
				}
				d := distance(current, order.Position)
				if d < nearestDistance || (math.Abs(d-nearestDistance) < 1e-9 && (nearestID == "" || order.ID < nearestID)) {
					nearestDistance = d
					nearestIndex = i
					nearestID = order.ID
				}
			}

			if nearestIndex == -1 {
				break
			}

			selected := orders[nearestIndex]
			orderIDs = append(orderIDs, selected.ID)
			assignedOrders = append(assignedOrders, selected)
			totalDistance += nearestDistance
			current = selected.Position
			assigned[selected.ID] = true
		}

		assignments = append(assignments, Assignment{
			RiderID:       rider.ID,
			OrderIDs:      orderIDs,
			TotalDistance: totalDistance,
		})
	}

	unassigned := make([]string, 0)
	for _, order := range orders {
		if !assigned[order.ID] {
			unassigned = append(unassigned, order.ID)
		}
	}
	sort.Strings(unassigned)
	return assignments, unassigned
}

func distance(a, b Position) float64 {
	return optimizer.HaversineDistance(
		optimizer.Position{Lat: a.Lat, Lng: a.Lng},
		optimizer.Position{Lat: b.Lat, Lng: b.Lng},
	)
}

func writeJSON(w http.ResponseWriter, status int, payload any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	encoder := json.NewEncoder(w)
	encoder.SetEscapeHTML(false)
	_ = encoder.Encode(payload)
}

func writeError(w http.ResponseWriter, status int, message string) {
	writeJSON(w, status, errorResponse{Error: message})
}
