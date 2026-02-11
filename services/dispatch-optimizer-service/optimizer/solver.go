package optimizer

import (
	"context"
	"math"
	"sort"
	"sync"
	"time"
)

// Default configuration values for [SolverConfig].
const (
	DefaultMaxIterations      = 1000
	DefaultTimeoutMs          = 5000
	DefaultWeightDeliveryTime = 0.5
	DefaultWeightIdleTime     = 0.2
	DefaultWeightSLABreach    = 0.3
	DefaultMaxOrdersPerRider  = 2
	DefaultMaxConsecutive     = 8
	DefaultNewRiderMaxKm      = 3.0
	DefaultTrafficFactor      = 1.0
	// batchETAThreshold is the maximum extra minutes tolerated when batching.
	batchETAThreshold = 3.0
)

// SolverConfig tunes the multi-objective solver behaviour.
type SolverConfig struct {
	// MaxIterations caps the post-optimisation swap passes.
	MaxIterations int `json:"max_iterations"`
	// TimeoutMs is the hard wall-clock timeout for a single Solve call.
	TimeoutMs int64 `json:"timeout_ms"`
	// WeightDeliveryTime is the objective weight for total delivery time (default 0.5).
	WeightDeliveryTime float64 `json:"weight_delivery_time"`
	// WeightIdleTime is the objective weight for rider idle time (default 0.2).
	WeightIdleTime float64 `json:"weight_idle_time"`
	// WeightSLABreach is the objective weight for SLA breach probability (default 0.3).
	WeightSLABreach float64 `json:"weight_sla_breach"`
	// MaxOrdersPerRider is the maximum number of concurrent active orders (default 2).
	MaxOrdersPerRider int `json:"max_orders_per_rider"`
	// MaxConsecutive is the maximum consecutive deliveries before a mandatory
	// 30-minute break (default 8).
	MaxConsecutive int `json:"max_consecutive"`
	// NewRiderMaxKm is the maximum assignment distance in km for riders with
	// fewer than 50 total deliveries (default 3.0).
	NewRiderMaxKm float64 `json:"new_rider_max_km"`
}

// withDefaults returns a copy of cfg with zero-valued fields replaced by their
// defaults.
func (cfg SolverConfig) withDefaults() SolverConfig {
	if cfg.MaxIterations <= 0 {
		cfg.MaxIterations = DefaultMaxIterations
	}
	if cfg.TimeoutMs <= 0 {
		cfg.TimeoutMs = DefaultTimeoutMs
	}
	if cfg.WeightDeliveryTime <= 0 {
		cfg.WeightDeliveryTime = DefaultWeightDeliveryTime
	}
	if cfg.WeightIdleTime <= 0 {
		cfg.WeightIdleTime = DefaultWeightIdleTime
	}
	if cfg.WeightSLABreach <= 0 {
		cfg.WeightSLABreach = DefaultWeightSLABreach
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
	return cfg
}

// RiderState captures the current operational state of a single rider.
type RiderState struct {
	// ID is the unique identifier for the rider.
	ID string `json:"id"`
	// Position is the rider's current GPS coordinate.
	Position Position `json:"position"`
	// Zone is the operational zone the rider is assigned to (empty = any).
	Zone string `json:"zone,omitempty"`
	// ActiveOrders is the number of orders currently being delivered.
	ActiveOrders int `json:"active_orders"`
	// ConsecutiveDeliveries is the number of deliveries completed since the
	// last break.
	ConsecutiveDeliveries int `json:"consecutive_deliveries"`
	// TotalDeliveries is the lifetime delivery count (used for new-rider checks).
	TotalDeliveries int `json:"total_deliveries"`
	// AvgSpeedKmh is the rider's recent average speed in km/h.
	AvgSpeedKmh float64 `json:"avg_speed_kmh"`
	// IsAvailable indicates whether the rider can accept new orders.
	IsAvailable bool `json:"is_available"`
	// LastBreakAt records when the rider last took a mandatory break.
	LastBreakAt time.Time `json:"last_break_at"`
	// VehicleType is "bicycle", "scooter", or "car".
	VehicleType string `json:"vehicle_type"`
	// BatteryPercent is the remaining charge for electric vehicles (0–100).
	BatteryPercent float64 `json:"battery_percent"`
}

// OrderRequest represents a single pending delivery order.
type OrderRequest struct {
	// ID is the unique identifier for the order.
	ID string `json:"id"`
	// Position is the delivery destination.
	Position Position `json:"position"`
	// Zone is the operational zone the order belongs to (empty = any).
	Zone string `json:"zone,omitempty"`
	// ItemCount is the number of items in the order.
	ItemCount int `json:"item_count"`
	// Weight is the total order weight in kg.
	Weight float64 `json:"weight"`
	// CreatedAt is the order creation timestamp.
	CreatedAt time.Time `json:"created_at"`
	// SLADeadline is the time by which the order must be delivered (typically
	// 10 minutes from creation).
	SLADeadline time.Time `json:"sla_deadline"`
	// IsExpressOrder flags orders that should be prioritised.
	IsExpressOrder bool `json:"is_express_order"`
}

// SolverResult contains the output of a single [Solver.Solve] invocation.
type SolverResult struct {
	// Assignments is the set of optimal rider–order pairings.
	Assignments []OptimalAssignment `json:"assignments"`
	// UnassignedOrders lists order IDs that could not be feasibly assigned.
	UnassignedOrders []string `json:"unassigned_orders"`
	// TotalCost is the aggregate weighted cost across all assignments.
	TotalCost float64 `json:"total_cost"`
	// SolveDurationMs is the wall-clock solve time in milliseconds.
	SolveDurationMs int64 `json:"solve_duration_ms"`
	// Metrics contains aggregate quality indicators.
	Metrics SolverMetrics `json:"metrics"`
}

// OptimalAssignment describes a single rider's optimised delivery plan.
type OptimalAssignment struct {
	// RiderID is the assigned rider.
	RiderID string `json:"rider_id"`
	// OrderIDs are the orders assigned to this rider.
	OrderIDs []string `json:"order_ids"`
	// EstimatedETAMinutes is the expected end-to-end delivery time.
	EstimatedETAMinutes float64 `json:"estimated_eta_minutes"`
	// TotalDistanceKm is the total travel distance.
	TotalDistanceKm float64 `json:"total_distance_km"`
	// SLABreachRisk is a probability [0.0, 1.0] that the SLA will be breached.
	SLABreachRisk float64 `json:"sla_breach_risk"`
	// RouteWaypoints lists the ordered positions the rider should visit.
	RouteWaypoints []Position `json:"route_waypoints"`
}

// SolverMetrics aggregates quality indicators for the full solution.
type SolverMetrics struct {
	// TotalDistance is the sum of all assignment distances in km.
	TotalDistance float64 `json:"total_distance"`
	// AvgDeliveryTime is the mean estimated delivery time in minutes.
	AvgDeliveryTime float64 `json:"avg_delivery_time"`
	// SLABreachCount is the number of assignments with breach risk > 0.5.
	SLABreachCount int `json:"sla_breach_count"`
	// BatchedOrders counts orders that were batched with at least one other.
	BatchedOrders int `json:"batched_orders"`
}

// Solver performs multi-objective dispatch optimisation. It is safe for
// concurrent use; call [NewSolver] to create an instance.
type Solver struct {
	cfg     SolverConfig
	checker *ConstraintChecker
	mu      sync.Mutex // guards nothing mutable today; reserved for future caches
}

// NewSolver creates a Solver with the supplied configuration. Zero-valued
// fields in cfg are replaced with production defaults.
func NewSolver(cfg SolverConfig) *Solver {
	cfg = cfg.withDefaults()
	return &Solver{
		cfg:     cfg,
		checker: NewConstraintChecker(cfg),
	}
}

// Solve computes optimal rider–order assignments using:
//  1. Cost-matrix construction (Haversine distances + multi-objective weights)
//  2. Hard constraint filtering (capacity, zone, battery, consecutive, new-rider)
//  3. Multi-order batching for geographically proximate orders
//  4. Modified Hungarian assignment for optimal matching
//  5. Post-optimisation swap passes to improve total cost
//  6. SLA breach probability calculation per assignment
//
// The method respects context cancellation and the configured timeout.
func (s *Solver) Solve(ctx context.Context, riders []RiderState, orders []OrderRequest) (*SolverResult, error) {
	start := time.Now()

	timeout := time.Duration(s.cfg.TimeoutMs) * time.Millisecond
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	// --- Filter available riders ---
	available := make([]RiderState, 0, len(riders))
	for _, r := range riders {
		if r.IsAvailable {
			available = append(available, r)
		}
	}
	if len(available) == 0 {
		return s.emptyResult(orders, start), nil
	}
	if len(orders) == 0 {
		return &SolverResult{SolveDurationMs: time.Since(start).Milliseconds()}, nil
	}

	// --- Step 1: Build cost matrix ---
	costMatrix := s.buildCostMatrix(available, orders)

	// --- Step 2 & 3: Feasibility mask + batching ---
	feasible := s.buildFeasibilityMask(available, orders)
	batches := BatchCompatible(orders, batchETAThreshold)

	// --- Step 4: Modified assignment ---
	assignments := s.hungarianAssign(ctx, available, orders, costMatrix, feasible, batches)

	// --- Step 5: Post-optimisation swaps ---
	assignments = s.postOptimise(ctx, available, orders, assignments, costMatrix, feasible)

	// --- Step 6: Build result ---
	result := s.buildResult(available, orders, assignments, start)

	// --- Record metrics ---
	s.recordMetrics(result)

	return result, ctx.Err()
}

// buildCostMatrix computes the weighted cost of assigning each (rider, order)
// pair. Dimensions: [len(riders)][len(orders)].
func (s *Solver) buildCostMatrix(riders []RiderState, orders []OrderRequest) [][]float64 {
	now := time.Now()
	matrix := make([][]float64, len(riders))
	for i, rider := range riders {
		matrix[i] = make([]float64, len(orders))
		for j, order := range orders {
			dist := HaversineDistance(rider.Position, order.Position)
			speed := rider.AvgSpeedKmh
			if speed <= 0 {
				speed = 20.0 // conservative fallback
			}
			etaMin := EstimateETAMinutes(dist, speed, DefaultTrafficFactor)

			// Idle time component: time rider has been waiting.
			idleMin := 0.0
			if rider.ActiveOrders == 0 {
				idleMin = etaMin // penalise further travel for idle riders less
			}

			// SLA breach probability: ratio of ETA to remaining SLA window.
			slaRisk := s.computeSLARisk(order, etaMin, now)

			matrix[i][j] = s.cfg.WeightDeliveryTime*etaMin +
				s.cfg.WeightIdleTime*idleMin +
				s.cfg.WeightSLABreach*slaRisk*100 // scale risk to comparable magnitude
		}
	}
	return matrix
}

// buildFeasibilityMask returns a boolean matrix where feasible[i][j] is true
// iff rider i may serve order j according to all hard constraints.
func (s *Solver) buildFeasibilityMask(riders []RiderState, orders []OrderRequest) [][]bool {
	mask := make([][]bool, len(riders))
	for i, rider := range riders {
		mask[i] = make([]bool, len(orders))
		for j, order := range orders {
			ok, _ := s.checker.CheckAll(rider, order)
			mask[i][j] = ok
		}
	}
	return mask
}

// hungarianAssign performs a modified assignment using a greedy auction
// approach on the cost matrix, respecting feasibility and batch groups.
// Returns a map: rider-index → list of order-indices.
func (s *Solver) hungarianAssign(
	ctx context.Context,
	riders []RiderState,
	orders []OrderRequest,
	cost [][]float64,
	feasible [][]bool,
	batches [][]int,
) map[int][]int {
	nR := len(riders)
	nO := len(orders)
	assignments := make(map[int][]int, nR)
	orderAssigned := make([]bool, nO)
	riderLoad := make([]int, nR)

	// Build a batch lookup: orderIndex → batchIndex.
	orderBatch := make(map[int]int, nO)
	for bi, batch := range batches {
		for _, oi := range batch {
			orderBatch[oi] = bi
		}
	}

	// Priority queue: sort all feasible (rider, order) edges by cost.
	type edge struct {
		rider, order int
		cost         float64
	}
	edges := make([]edge, 0, nR*nO)
	for i := 0; i < nR; i++ {
		for j := 0; j < nO; j++ {
			if feasible[i][j] {
				edges = append(edges, edge{rider: i, order: j, cost: cost[i][j]})
			}
		}
	}
	sort.Slice(edges, func(a, b int) bool {
		return edges[a].cost < edges[b].cost
	})

	maxPerRider := s.cfg.MaxOrdersPerRider
	if maxPerRider <= 0 {
		maxPerRider = DefaultMaxOrdersPerRider
	}

	for _, e := range edges {
		if ctx.Err() != nil {
			break
		}
		if orderAssigned[e.order] {
			continue
		}
		if riderLoad[e.rider] >= maxPerRider {
			continue
		}

		// Attempt to assign entire batch if possible.
		bi, inBatch := orderBatch[e.order]
		if inBatch && len(batches[bi]) > 1 {
			batch := batches[bi]
			canAssignBatch := true
			batchCount := 0
			for _, oi := range batch {
				if orderAssigned[oi] {
					continue
				}
				if !feasible[e.rider][oi] {
					canAssignBatch = false
					break
				}
				batchCount++
			}
			if canAssignBatch && riderLoad[e.rider]+batchCount <= maxPerRider {
				for _, oi := range batch {
					if orderAssigned[oi] {
						continue
					}
					assignments[e.rider] = append(assignments[e.rider], oi)
					orderAssigned[oi] = true
					riderLoad[e.rider]++
				}
				continue
			}
		}

		// Single order assignment fallback.
		assignments[e.rider] = append(assignments[e.rider], e.order)
		orderAssigned[e.order] = true
		riderLoad[e.rider]++
	}

	return assignments
}

// postOptimise performs iterative swap passes: for each pair of riders, it
// checks whether swapping one order between them improves total cost.
func (s *Solver) postOptimise(
	ctx context.Context,
	riders []RiderState,
	orders []OrderRequest,
	assignments map[int][]int,
	cost [][]float64,
	feasible [][]bool,
) map[int][]int {
	maxIter := s.cfg.MaxIterations
	if maxIter <= 0 {
		maxIter = DefaultMaxIterations
	}

	maxPerRider := s.cfg.MaxOrdersPerRider
	if maxPerRider <= 0 {
		maxPerRider = DefaultMaxOrdersPerRider
	}

	for iter := 0; iter < maxIter; iter++ {
		if ctx.Err() != nil {
			break
		}
		improved := false

		riderIdxs := make([]int, 0, len(assignments))
		for ri := range assignments {
			riderIdxs = append(riderIdxs, ri)
		}
		sort.Ints(riderIdxs)

		for ai := 0; ai < len(riderIdxs); ai++ {
			for bi := ai + 1; bi < len(riderIdxs); bi++ {
				ra := riderIdxs[ai]
				rb := riderIdxs[bi]

				for oa := 0; oa < len(assignments[ra]); oa++ {
					oi := assignments[ra][oa]
					if !feasible[rb][oi] {
						continue
					}
					if len(assignments[rb]) >= maxPerRider {
						continue
					}

					currentCost := cost[ra][oi]
					newCost := cost[rb][oi]
					if newCost < currentCost {
						// Move order from ra to rb.
						assignments[rb] = append(assignments[rb], oi)
						assignments[ra] = append(assignments[ra][:oa], assignments[ra][oa+1:]...)
						improved = true
						break
					}
				}
			}
		}
		if !improved {
			break
		}
	}

	return assignments
}

// buildResult converts internal assignment indices into the public SolverResult
// including SLA breach risk, route waypoints and aggregate metrics.
func (s *Solver) buildResult(
	riders []RiderState,
	orders []OrderRequest,
	assignments map[int][]int,
	start time.Time,
) *SolverResult {
	now := time.Now()
	result := &SolverResult{}
	assignedSet := make(map[int]bool, len(orders))

	var totalDist, totalETA float64
	var totalAssigned int

	for ri, orderIdxs := range assignments {
		if len(orderIdxs) == 0 {
			continue
		}
		rider := riders[ri]
		speed := rider.AvgSpeedKmh
		if speed <= 0 {
			speed = 20.0
		}

		var dist float64
		var maxETA float64
		var maxRisk float64
		orderIDs := make([]string, 0, len(orderIdxs))
		waypoints := []Position{rider.Position}
		current := rider.Position

		for _, oi := range orderIdxs {
			order := orders[oi]
			d := HaversineDistance(current, order.Position)
			dist += d
			eta := EstimateETAMinutes(d, speed, DefaultTrafficFactor)
			if eta > maxETA {
				maxETA = eta
			}
			risk := s.computeSLARisk(order, eta, now)
			if risk > maxRisk {
				maxRisk = risk
			}
			orderIDs = append(orderIDs, order.ID)
			waypoints = append(waypoints, order.Position)
			current = order.Position
			assignedSet[oi] = true
		}

		a := OptimalAssignment{
			RiderID:             rider.ID,
			OrderIDs:            orderIDs,
			EstimatedETAMinutes: maxETA,
			TotalDistanceKm:     dist,
			SLABreachRisk:       maxRisk,
			RouteWaypoints:      waypoints,
		}
		result.Assignments = append(result.Assignments, a)
		totalDist += dist
		totalETA += maxETA
		totalAssigned += len(orderIDs)

		if maxRisk > 0.5 {
			result.Metrics.SLABreachCount++
		}
		if len(orderIDs) > 1 {
			result.Metrics.BatchedOrders += len(orderIDs)
		}
	}

	// Sort assignments by rider ID for deterministic output.
	sort.Slice(result.Assignments, func(i, j int) bool {
		return result.Assignments[i].RiderID < result.Assignments[j].RiderID
	})

	// Collect unassigned orders.
	for oi, order := range orders {
		if !assignedSet[oi] {
			result.UnassignedOrders = append(result.UnassignedOrders, order.ID)
		}
	}
	sort.Strings(result.UnassignedOrders)

	// Aggregate metrics.
	result.Metrics.TotalDistance = totalDist
	if totalAssigned > 0 {
		result.Metrics.AvgDeliveryTime = totalETA / float64(len(result.Assignments))
	}
	result.TotalCost = s.cfg.WeightDeliveryTime*totalETA +
		s.cfg.WeightSLABreach*float64(result.Metrics.SLABreachCount)*100
	result.SolveDurationMs = time.Since(start).Milliseconds()

	return result
}

// computeSLARisk returns a probability [0, 1] that the given order will breach
// its SLA deadline given the estimated ETA in minutes.
func (s *Solver) computeSLARisk(order OrderRequest, etaMin float64, now time.Time) float64 {
	remaining := time.Until(order.SLADeadline).Minutes()
	if order.SLADeadline.IsZero() {
		remaining = time.Until(order.CreatedAt.Add(10 * time.Minute)).Minutes()
	}
	if remaining <= 0 {
		return 1.0
	}
	ratio := etaMin / remaining
	// Sigmoid-like scaling: stays near 0 when ratio < 0.5, rises steeply past 0.8.
	risk := 1.0 / (1.0 + math.Exp(-10*(ratio-0.7)))
	if risk > 1.0 {
		risk = 1.0
	}
	if risk < 0.0 {
		risk = 0.0
	}
	return risk
}

// emptyResult returns a SolverResult when no riders are available.
func (s *Solver) emptyResult(orders []OrderRequest, start time.Time) *SolverResult {
	ids := make([]string, len(orders))
	for i, o := range orders {
		ids[i] = o.ID
	}
	sort.Strings(ids)
	return &SolverResult{
		UnassignedOrders: ids,
		SolveDurationMs:  time.Since(start).Milliseconds(),
	}
}

// recordMetrics pushes solver-run data into Prometheus collectors.
func (s *Solver) recordMetrics(result *SolverResult) {
	SolveDuration.Observe(float64(result.SolveDurationMs) / 1000.0)

	assigned := 0
	for _, a := range result.Assignments {
		assigned += len(a.OrderIDs)
		SLABreachRisk.Observe(a.SLABreachRisk)
		AvgDeliveryDistance.Observe(a.TotalDistanceKm)
	}
	AssignedOrders.Add(float64(assigned))
	UnassignedOrders.Add(float64(len(result.UnassignedOrders)))
	BatchedOrders.Add(float64(result.Metrics.BatchedOrders))
}
