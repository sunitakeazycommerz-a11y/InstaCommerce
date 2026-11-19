package optimizer

import (
	"context"
	"sort"
	"time"
)

// Solver performs multi-objective dispatch optimisation.
type Solver struct {
	cfg     SolverConfig
	checker *ConstraintChecker
}

// NewSolver creates a Solver with production defaults for unset fields.
func NewSolver(cfg SolverConfig) *Solver {
	cfg = cfg.withDefaults()
	return &Solver{
		cfg:     cfg,
		checker: NewConstraintChecker(cfg),
	}
}

// Solve computes optimal rider-order assignments obeying capacity, bag weight/volume,
// cold-chain, zone, battery, consecutive-delivery, and distance constraints.
func (s *Solver) Solve(ctx context.Context, riders []RiderState, orders []OrderRequest) (*SolverResult, error) {
	start := time.Now()

	timeout := time.Duration(s.cfg.TimeoutMs) * time.Millisecond
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

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

	costMatrix, priorityCost := s.buildCostMatrix(available, orders)
	feasible := s.buildFeasibilityMask(available, orders)
	batches := BatchCompatibleWithDetour(orders, batchETAThreshold, s.cfg.MaxBatchDetourKm)

	assignments := s.hungarianAssign(ctx, available, orders, priorityCost, feasible, batches)
	assignments = s.postOptimise(ctx, available, orders, assignments, costMatrix, feasible)
	result := s.buildResult(available, orders, assignments, start)
	s.recordMetrics(result)

	return result, ctx.Err()
}

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
