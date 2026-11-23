package optimizer

import (
	"strings"
	"time"
)

const (
	DefaultMaxIterations        = 1000
	DefaultTimeoutMs            = 5000
	DefaultWeightDeliveryTime   = 0.5
	DefaultWeightIdleTime       = 0.2
	DefaultWeightSLABreach      = 0.3
	DefaultMaxOrdersPerRider    = 2
	DefaultMaxConsecutive       = 8
	DefaultNewRiderMaxKm        = 3.0
	DefaultTrafficFactor        = 1.0
	DefaultMaxBatchDetourKm     = 1.5
	DefaultMaxStoreColocationKm = 0.15
	DefaultColdChainBoost       = 12.0
	batchETAThreshold           = 3.0
)

// SolverConfig tunes the multi-objective solver behaviour.
type SolverConfig struct {
	MaxIterations          int     `json:"max_iterations"`
	TimeoutMs              int64   `json:"timeout_ms"`
	WeightDeliveryTime     float64 `json:"weight_delivery_time"`
	WeightIdleTime         float64 `json:"weight_idle_time"`
	WeightSLABreach        float64 `json:"weight_sla_breach"`
	MaxOrdersPerRider      int     `json:"max_orders_per_rider"`
	MaxConsecutive         int     `json:"max_consecutive"`
	NewRiderMaxKm          float64 `json:"new_rider_max_km"`
	MaxBatchDetourKm       float64 `json:"max_batch_detour_km,omitempty"`
	ColdChainPriorityBoost float64 `json:"cold_chain_priority_boost,omitempty"`
}

func (cfg SolverConfig) withDefaults() SolverConfig {
	if cfg.MaxIterations <= 0 {
		cfg.MaxIterations = DefaultMaxIterations
	}
	if cfg.TimeoutMs <= 0 {
		cfg.TimeoutMs = DefaultTimeoutMs
	}
	if cfg.WeightDeliveryTime <= 0 && cfg.WeightIdleTime <= 0 && cfg.WeightSLABreach <= 0 {
		cfg.WeightDeliveryTime = DefaultWeightDeliveryTime
		cfg.WeightIdleTime = DefaultWeightIdleTime
		cfg.WeightSLABreach = DefaultWeightSLABreach
	} else {
		if cfg.WeightDeliveryTime < 0 {
			cfg.WeightDeliveryTime = 0
		}
		if cfg.WeightIdleTime < 0 {
			cfg.WeightIdleTime = 0
		}
		if cfg.WeightSLABreach < 0 {
			cfg.WeightSLABreach = 0
		}
	}
	if cfg.MaxOrdersPerRider <= 0 {
		cfg.MaxOrdersPerRider = DefaultMaxOrdersPerRider
	}
	if cfg.MaxConsecutive <= 0 {
		cfg.MaxConsecutive = DefaultMaxConsecutive
	}
	if cfg.NewRiderMaxKm <= 0 {
		cfg.NewRiderMaxKm = DefaultNewRiderMaxKm
	}
	if cfg.MaxBatchDetourKm <= 0 {
		cfg.MaxBatchDetourKm = DefaultMaxBatchDetourKm
	}
	if cfg.ColdChainPriorityBoost <= 0 {
		cfg.ColdChainPriorityBoost = DefaultColdChainBoost
	}
	return cfg
}

// RiderState captures the current operational state of a single rider.
type RiderState struct {
	ID                     string    `json:"id"`
	Position               Position  `json:"position"`
	Zone                   string    `json:"zone,omitempty"`
	ActiveOrders           int       `json:"active_orders"`
	ConsecutiveDeliveries  int       `json:"consecutive_deliveries"`
	TotalDeliveries        int       `json:"total_deliveries"`
	AvgRating              float64   `json:"avg_rating,omitempty"`
	AvgSpeedKmh            float64   `json:"avg_speed_kmh"`
	IsAvailable            bool      `json:"is_available"`
	LastBreakAt            time.Time `json:"last_break_at"`
	VehicleType            string    `json:"vehicle_type"`
	BatteryPercent         float64   `json:"battery_percent"`
	CurrentBagWeightKg     float64   `json:"current_bag_weight_kg,omitempty"`
	CurrentBagVolumeLiters float64   `json:"current_bag_volume_liters,omitempty"`
	MaxBagWeightKg         float64   `json:"max_bag_weight_kg,omitempty"`
	MaxBagVolumeLiters     float64   `json:"max_bag_volume_liters,omitempty"`
	HasThermalBag          bool      `json:"has_thermal_bag,omitempty"`
}

// OrderRequest represents an incoming customer order awaiting rider assignment.
type OrderRequest struct {
	ID                string    `json:"id"`
	StoreID           string    `json:"store_id,omitempty"`
	StorePosition     *Position `json:"store_position,omitempty"`
	Position          Position  `json:"position"`
	Zone              string    `json:"zone,omitempty"`
	ItemCount         int       `json:"item_count"`
	Weight            float64   `json:"weight"`
	VolumeLiters      float64   `json:"volume_liters,omitempty"`
	CreatedAt         time.Time `json:"created_at"`
	SLADeadline       time.Time `json:"sla_deadline"`
	IsExpressOrder    bool      `json:"is_express_order"`
	RequiresColdChain bool      `json:"requires_cold_chain,omitempty"`
	ColdChainTier     string    `json:"cold_chain_tier,omitempty"`
}

func (o OrderRequest) isColdChain() bool {
	if o.RequiresColdChain {
		return true
	}
	tier := strings.ToLower(strings.TrimSpace(o.ColdChainTier))
	return tier == "frozen" || tier == "chilled"
}

func (o OrderRequest) isFrozen() bool {
	return strings.EqualFold(strings.TrimSpace(o.ColdChainTier), "frozen")
}

// SolverResult contains the output of a single [Solver.Solve] invocation.
type SolverResult struct {
	Assignments      []OptimalAssignment `json:"assignments"`
	UnassignedOrders []string            `json:"unassigned_orders"`
	TotalCost        float64             `json:"total_cost"`
	SolveDurationMs  int64               `json:"solve_duration_ms"`
	Metrics          SolverMetrics       `json:"metrics"`
}

// OptimalAssignment describes a single rider's optimised delivery plan.
type OptimalAssignment struct {
	RiderID             string     `json:"rider_id"`
	OrderIDs            []string   `json:"order_ids"`
	EstimatedETAMinutes float64    `json:"estimated_eta_minutes"`
	TotalDistanceKm     float64    `json:"total_distance_km"`
	SLABreachRisk       float64    `json:"sla_breach_risk"`
	RouteWaypoints      []Position `json:"route_waypoints"`
}

// SolverMetrics aggregates quality indicators for the full solution.
type SolverMetrics struct {
	TotalDistance   float64 `json:"total_distance"`
	AvgDeliveryTime float64 `json:"avg_delivery_time"`
	SLABreachCount  int     `json:"sla_breach_count"`
	BatchedOrders   int     `json:"batched_orders"`
}

type bagLimits struct {
	maxWeightKg     float64
	maxVolumeLiters float64
}

var vehicleBagDefaults = map[string]bagLimits{
	"bicycle":          {maxWeightKg: 8.0, maxVolumeLiters: 30.0},
	"bike":             {maxWeightKg: 8.0, maxVolumeLiters: 30.0},
	"cycle":            {maxWeightKg: 8.0, maxVolumeLiters: 30.0},
	"e_bike":           {maxWeightKg: 8.0, maxVolumeLiters: 30.0},
	"ebike":            {maxWeightKg: 8.0, maxVolumeLiters: 30.0},
	"electric_bicycle": {maxWeightKg: 8.0, maxVolumeLiters: 30.0},
	"electric_bike":    {maxWeightKg: 8.0, maxVolumeLiters: 30.0},
	"scooter":          {maxWeightKg: 20.0, maxVolumeLiters: 45.0},
	"electric_scooter": {maxWeightKg: 20.0, maxVolumeLiters: 45.0},
	"e_scooter":        {maxWeightKg: 20.0, maxVolumeLiters: 45.0},
	"escooter":         {maxWeightKg: 20.0, maxVolumeLiters: 45.0},
	"motorcycle":       {maxWeightKg: 20.0, maxVolumeLiters: 45.0},
	"motorbike":        {maxWeightKg: 20.0, maxVolumeLiters: 45.0},
	"ev_2w":            {maxWeightKg: 20.0, maxVolumeLiters: 45.0},
	"ev":               {maxWeightKg: 20.0, maxVolumeLiters: 45.0},
	"car":              {maxWeightKg: 60.0, maxVolumeLiters: 150.0},
	"van":              {maxWeightKg: 60.0, maxVolumeLiters: 150.0},
	"four_wheeler":     {maxWeightKg: 60.0, maxVolumeLiters: 150.0},
}

func effectiveBagLimits(r RiderState) (float64, float64) {
	limits := bagLimits{maxWeightKg: 25.0, maxVolumeLiters: 60.0}
	if def, ok := vehicleBagDefaults[normalizeVehicleType(r.VehicleType)]; ok {
		limits = def
	}
	if r.MaxBagWeightKg > 0 {
		limits.maxWeightKg = r.MaxBagWeightKg
	}
	if r.MaxBagVolumeLiters > 0 {
		limits.maxVolumeLiters = r.MaxBagVolumeLiters
	}
	return limits.maxWeightKg, limits.maxVolumeLiters
}
