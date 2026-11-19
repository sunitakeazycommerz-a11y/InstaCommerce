package optimizer

import (
	"fmt"
	"strings"
	"time"
)

// ConstraintViolation describes a single reason why a rider cannot be assigned an order.
type ConstraintViolation struct {
	Constraint string `json:"constraint"`
	Rider      string `json:"rider"`
	Order      string `json:"order"`
	Reason     string `json:"reason"`
}

// ConstraintChecker validates rider-order feasibility against production constraints.
type ConstraintChecker struct {
	config SolverConfig
}

// NewConstraintChecker creates a ConstraintChecker with cfg.
func NewConstraintChecker(cfg SolverConfig) *ConstraintChecker {
	return &ConstraintChecker{config: cfg}
}

// CheckAll runs every hard constraint and returns feasibility plus any violations.
func (c *ConstraintChecker) CheckAll(rider RiderState, order OrderRequest) (bool, []ConstraintViolation) {
	var violations []ConstraintViolation
	checks := []func(RiderState, OrderRequest) *ConstraintViolation{
		c.checkCapacity,
		c.checkZone,
		c.checkBattery,
		c.checkConsecutiveLimit,
		c.checkNewRiderDistance,
		c.checkVehicleSuitability,
		c.checkBagCapacity,
		c.checkColdChain,
	}
	for _, fn := range checks {
		if v := fn(rider, order); v != nil {
			violations = append(violations, *v)
		}
	}
	return len(violations) == 0, violations
}

func (c *ConstraintChecker) checkCapacity(rider RiderState, order OrderRequest) *ConstraintViolation {
	max := c.config.MaxOrdersPerRider
	if max <= 0 {
		max = DefaultMaxOrdersPerRider
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

func (c *ConstraintChecker) checkZone(rider RiderState, order OrderRequest) *ConstraintViolation {
	if rider.Zone == "" || order.Zone == "" || rider.Zone == order.Zone {
		return nil
	}
	return &ConstraintViolation{
		Constraint: "zone",
		Rider:      rider.ID,
		Order:      order.ID,
		Reason:     fmt.Sprintf("rider zone %q != order zone %q", rider.Zone, order.Zone),
	}
}

func (c *ConstraintChecker) checkBattery(rider RiderState, order OrderRequest) *ConstraintViolation {
	if !isElectricVehicle(rider.VehicleType) {
		return nil
	}
	dist := HaversineDistance(rider.Position, order.Position)
	if dist > 3.0 && rider.BatteryPercent < 20.0 {
		return &ConstraintViolation{
			Constraint: "battery",
			Rider:      rider.ID,
			Order:      order.ID,
			Reason: fmt.Sprintf(
				"battery at %.1f%% below 20.0%% threshold for delivery distance %.2f km (> 3.0 km); redirecting to battery swap station",
				rider.BatteryPercent, dist,
			),
		}
	}
	const minBattery = 15.0
	if rider.BatteryPercent < minBattery {
		return &ConstraintViolation{
			Constraint: "battery",
			Rider:      rider.ID,
			Order:      order.ID,
			Reason: fmt.Sprintf(
				"battery at %.1f%% (minimum %.1f%%); redirecting to battery swap station",
				rider.BatteryPercent, minBattery,
			),
		}
	}
	return nil
}

func (c *ConstraintChecker) checkConsecutiveLimit(rider RiderState, order OrderRequest) *ConstraintViolation {
	max := c.config.MaxConsecutive
	if max <= 0 {
		max = DefaultMaxConsecutive
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

func (c *ConstraintChecker) checkNewRiderDistance(rider RiderState, order OrderRequest) *ConstraintViolation {
	const newRiderThreshold = 50
	if rider.TotalDeliveries >= newRiderThreshold {
		return nil
	}
	maxKm := c.config.NewRiderMaxKm
	if maxKm <= 0 {
		maxKm = DefaultNewRiderMaxKm
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

func (c *ConstraintChecker) checkVehicleSuitability(rider RiderState, order OrderRequest) *ConstraintViolation {
	const maxBicycleWeightKg = 8.0
	if isBicycleVehicle(rider.VehicleType) && order.Weight > maxBicycleWeightKg {
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

func (c *ConstraintChecker) checkBagCapacity(rider RiderState, order OrderRequest) *ConstraintViolation {
	maxWeight, maxVolume := effectiveBagLimits(rider)
	if order.Weight > 0 && rider.CurrentBagWeightKg+order.Weight > maxWeight {
		return &ConstraintViolation{
			Constraint: "bag_capacity",
			Rider:      rider.ID,
			Order:      order.ID,
			Reason: fmt.Sprintf(
				"bag weight %.2f kg + order %.2f kg exceeds limit %.2f kg",
				rider.CurrentBagWeightKg, order.Weight, maxWeight,
			),
		}
	}
	if order.VolumeLiters > 0 && rider.CurrentBagVolumeLiters+order.VolumeLiters > maxVolume {
		return &ConstraintViolation{
			Constraint: "bag_capacity",
			Rider:      rider.ID,
			Order:      order.ID,
			Reason: fmt.Sprintf(
				"bag volume %.2f L + order %.2f L exceeds limit %.2f L",
				rider.CurrentBagVolumeLiters, order.VolumeLiters, maxVolume,
			),
		}
	}
	return nil
}

func (c *ConstraintChecker) checkColdChain(rider RiderState, order OrderRequest) *ConstraintViolation {
	if !order.isColdChain() || rider.HasThermalBag {
		return nil
	}
	return &ConstraintViolation{
		Constraint: "cold_chain",
		Rider:      rider.ID,
		Order:      order.ID,
		Reason:     "order requires cold-chain handling but rider lacks thermal bag",
	}
}

func normalizeVehicleType(vType string) string {
	s := strings.ToLower(strings.TrimSpace(vType))
	return strings.NewReplacer("-", "_", " ", "_").Replace(s)
}

func isElectricVehicle(vType string) bool {
	switch normalizeVehicleType(vType) {
	case "electric_scooter", "e_scooter", "escooter",
		"electric_bicycle", "e_bike", "ebike", "electric_bike",
		"ev_2w", "ev":
		return true
	default:
		return false
	}
}

func isBicycleVehicle(vType string) bool {
	switch normalizeVehicleType(vType) {
	case "bicycle", "bike", "cycle", "e_bike", "ebike", "electric_bicycle", "electric_bike":
		return true
	default:
		return false
	}
}
