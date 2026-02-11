package optimizer

import (
	"fmt"
	"time"
)

// ConstraintViolation describes a single reason why a rider cannot be assigned
// a particular order.
type ConstraintViolation struct {
	// Constraint is the canonical name of the violated constraint
	// (e.g. "capacity", "zone", "battery").
	Constraint string `json:"constraint"`
	// Rider is the ID of the rider involved.
	Rider string `json:"rider"`
	// Order is the ID of the order involved.
	Order string `json:"order"`
	// Reason is a human-readable explanation of the violation.
	Reason string `json:"reason"`
}

// ConstraintChecker validates rider–order feasibility against production
// constraints. It is safe for concurrent use; all methods are read-only after
// construction.
type ConstraintChecker struct {
	config SolverConfig
}

// NewConstraintChecker creates a ConstraintChecker with the given solver
// configuration.
func NewConstraintChecker(cfg SolverConfig) *ConstraintChecker {
	return &ConstraintChecker{config: cfg}
}

// CheckAll runs every hard constraint and returns whether the assignment is
// feasible together with a (possibly empty) list of violations.
func (c *ConstraintChecker) CheckAll(rider RiderState, order OrderRequest) (bool, []ConstraintViolation) {
	var violations []ConstraintViolation

	if v := c.checkCapacity(rider, order); v != nil {
		violations = append(violations, *v)
	}
	if v := c.checkZone(rider, order); v != nil {
		violations = append(violations, *v)
	}
	if v := c.checkBattery(rider, order); v != nil {
		violations = append(violations, *v)
	}
	if v := c.checkConsecutiveLimit(rider, order); v != nil {
		violations = append(violations, *v)
	}
	if v := c.checkNewRiderDistance(rider, order); v != nil {
		violations = append(violations, *v)
	}
	if v := c.checkVehicleSuitability(rider, order); v != nil {
		violations = append(violations, *v)
	}

	return len(violations) == 0, violations
}

// checkCapacity ensures the rider has not exceeded the maximum number of
// concurrent active orders.
func (c *ConstraintChecker) checkCapacity(rider RiderState, order OrderRequest) *ConstraintViolation {
	max := c.config.MaxOrdersPerRider
	if max <= 0 {
		max = 2
	}
	if rider.ActiveOrders >= max {
		return &ConstraintViolation{
			Constraint: "capacity",
			Rider:      rider.ID,
			Order:      order.ID,
			Reason:     fmt.Sprintf("rider already has %d active orders (max %d)", rider.ActiveOrders, max),
		}
	}
	return nil
}

// checkZone verifies that both rider and order belong to the same operational
// zone. If either zone is empty the constraint is treated as satisfied (zone
// info unavailable).
func (c *ConstraintChecker) checkZone(rider RiderState, order OrderRequest) *ConstraintViolation {
	if rider.Zone == "" || order.Zone == "" {
		return nil
	}
	if rider.Zone != order.Zone {
		return &ConstraintViolation{
			Constraint: "zone",
			Rider:      rider.ID,
			Order:      order.ID,
			Reason:     fmt.Sprintf("rider zone %q != order zone %q", rider.Zone, order.Zone),
		}
	}
	return nil
}

// checkBattery ensures riders on electric vehicles have enough battery to
// complete the delivery. A minimum of 15 % charge is required.
func (c *ConstraintChecker) checkBattery(rider RiderState, order OrderRequest) *ConstraintViolation {
	if rider.VehicleType != "bicycle" && rider.VehicleType != "scooter" {
		// Non-electric vehicles are not battery-constrained.
		return nil
	}
	const minBattery = 15.0
	if rider.BatteryPercent < minBattery {
		return &ConstraintViolation{
			Constraint: "battery",
			Rider:      rider.ID,
			Order:      order.ID,
			Reason:     fmt.Sprintf("battery at %.1f%% (minimum %.1f%%)", rider.BatteryPercent, minBattery),
		}
	}
	return nil
}

// checkConsecutiveLimit enforces a mandatory 30-minute break after a
// configurable number of consecutive deliveries (default 8).
func (c *ConstraintChecker) checkConsecutiveLimit(rider RiderState, order OrderRequest) *ConstraintViolation {
	max := c.config.MaxConsecutive
	if max <= 0 {
		max = 8
	}
	if rider.ConsecutiveDeliveries >= max {
		breakDue := rider.LastBreakAt.Add(30 * time.Minute)
		return &ConstraintViolation{
			Constraint: "consecutive_limit",
			Rider:      rider.ID,
			Order:      order.ID,
			Reason: fmt.Sprintf(
				"rider has %d consecutive deliveries (max %d), break due at %s",
				rider.ConsecutiveDeliveries, max, breakDue.Format(time.RFC3339),
			),
		}
	}
	return nil
}

// checkNewRiderDistance limits assignment distance for new riders (those with
// fewer than 50 total deliveries) to a configurable cap (default 3 km).
func (c *ConstraintChecker) checkNewRiderDistance(rider RiderState, order OrderRequest) *ConstraintViolation {
	const newRiderThreshold = 50
	if rider.TotalDeliveries >= newRiderThreshold {
		return nil
	}
	maxKm := c.config.NewRiderMaxKm
	if maxKm <= 0 {
		maxKm = 3.0
	}
	dist := HaversineDistance(rider.Position, order.Position)
	if dist > maxKm {
		return &ConstraintViolation{
			Constraint: "new_rider_distance",
			Rider:      rider.ID,
			Order:      order.ID,
			Reason: fmt.Sprintf(
				"new rider (deliveries=%d) distance %.2f km exceeds limit %.2f km",
				rider.TotalDeliveries, dist, maxKm,
			),
		}
	}
	return nil
}

// checkVehicleSuitability ensures that heavy or bulky orders are not assigned
// to bicycle riders. Orders above 8 kg require a scooter or car.
func (c *ConstraintChecker) checkVehicleSuitability(rider RiderState, order OrderRequest) *ConstraintViolation {
	const maxBicycleWeightKg = 8.0
	if rider.VehicleType == "bicycle" && order.Weight > maxBicycleWeightKg {
		return &ConstraintViolation{
			Constraint: "vehicle_suitability",
			Rider:      rider.ID,
			Order:      order.ID,
			Reason: fmt.Sprintf(
				"order weight %.2f kg exceeds bicycle limit %.2f kg",
				order.Weight, maxBicycleWeightKg,
			),
		}
	}
	return nil
}
