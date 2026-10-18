package optimizer

import (
	"context"
	"errors"
	"fmt"
	"time"
)

var (
	// ErrNoRidersAvailable is returned when no active, checked-in riders exist
	// within the candidate dispatch radius (Issue #576).
	ErrNoRidersAvailable = errors.New("no active riders available within dispatch radius")

	// ErrNilCostMatrix is returned if matrix allocation fails.
	ErrNilCostMatrix = errors.New("nil cost matrix in bipartite matching")
)

const (
	// DefaultMaxDispatchRadiusKm is the maximum radius (5.0 km) within which
	// a rider can be matched to a dark store dispatch request.
	DefaultMaxDispatchRadiusKm = 5.0

	// DefaultReevaluationInterval is the interval to retry matching when no riders are found.
	DefaultReevaluationInterval = 30 * time.Second

	// DefaultEscalationTimeout is the maximum duration an order can wait before
	// escalating to the operations dashboard (3 minutes).
	DefaultEscalationTimeout = 3 * time.Minute
)

// MatcherConfig configures the bipartite matching engine.
type MatcherConfig struct {
	MaxDispatchRadiusKm  float64
	ReevaluationInterval time.Duration
	EscalationTimeout    time.Duration
}

// BipartiteMatcher performs resilient Kuhn-Munkres matching between dark store
// orders and available fleet riders.
type BipartiteMatcher struct {
	cfg    MatcherConfig
	solver *Solver
}

// NewBipartiteMatcher creates a BipartiteMatcher with safe defaults.
func NewBipartiteMatcher(cfg *MatcherConfig, solver *Solver) *BipartiteMatcher {
	if cfg == nil {
		cfg = &MatcherConfig{
			MaxDispatchRadiusKm:  DefaultMaxDispatchRadiusKm,
			ReevaluationInterval: DefaultReevaluationInterval,
			EscalationTimeout:    DefaultEscalationTimeout,
		}
	}
	if cfg.MaxDispatchRadiusKm <= 0 {
		cfg.MaxDispatchRadiusKm = DefaultMaxDispatchRadiusKm
	}
	if cfg.ReevaluationInterval <= 0 {
		cfg.ReevaluationInterval = DefaultReevaluationInterval
	}
	if cfg.EscalationTimeout <= 0 {
		cfg.EscalationTimeout = DefaultEscalationTimeout
	}
	if solver == nil {
		solver = NewSolver(SolverConfig{})
	}
	return &BipartiteMatcher{
		cfg:    *cfg,
		solver: solver,
	}
}

// MatchResult extends SolverResult with dispatch escalation and retry metadata.
type MatchResult struct {
	*SolverResult
	NeedsReevaluation bool          `json:"needs_reevaluation"`
	RetryAfter        time.Duration `json:"retry_after,omitempty"`
	Escalated         bool          `json:"escalated"`
	EscalationReason  string        `json:"escalation_reason,omitempty"`
}

// Match executes bipartite matching with strict guard clauses to prevent nil matrix
// panics when candidate rider pools are empty (Fixes Issue #576).
func (m *BipartiteMatcher) Match(ctx context.Context, riders []RiderState, orders []OrderRequest) (*MatchResult, error) {
	// Guard 1: Zero riders supplied
	if len(riders) == 0 {
		return m.handleNoRiders(orders)
	}

	// Guard 2: Filter active riders within candidate dispatch radius (5km)
	candidateRiders := make([]RiderState, 0, len(riders))
	for _, r := range riders {
		if !r.IsAvailable {
			continue
		}
		// Rider must be within MaxDispatchRadiusKm of at least one order
		isNearAnyOrder := false
		if len(orders) == 0 {
			isNearAnyOrder = true
		} else {
			for _, o := range orders {
				dist := HaversineDistance(r.Position, o.Position)
				if dist <= m.cfg.MaxDispatchRadiusKm {
					isNearAnyOrder = true
					break
				}
			}
		}
		if isNearAnyOrder {
			candidateRiders = append(candidateRiders, r)
		}
	}

	// Guard 3: If candidate rider pool is empty within radius, return ErrNoRidersAvailable safely
	if len(candidateRiders) == 0 {
		return m.handleNoRiders(orders)
	}

	// If no orders to match, return empty result
	if len(orders) == 0 {
		res, err := m.solver.Solve(ctx, candidateRiders, orders)
		if err != nil {
			return nil, err
		}
		return &MatchResult{SolverResult: res}, nil
	}

	// Solve bipartite matching using solver
	solverRes, err := m.solver.Solve(ctx, candidateRiders, orders)
	if err != nil {
		return nil, fmt.Errorf("bipartite solver failed: %w", err)
	}

	matchRes := &MatchResult{
		SolverResult: solverRes,
	}

	// If any orders remain unassigned, check for escalation
	now := time.Now()
	for _, o := range orders {
		for _, unassignedID := range solverRes.UnassignedOrders {
			if o.ID == unassignedID {
				if !o.CreatedAt.IsZero() && now.Sub(o.CreatedAt) >= m.cfg.EscalationTimeout {
					matchRes.Escalated = true
					matchRes.EscalationReason = fmt.Sprintf("Order %s unassigned for >= %v", o.ID, m.cfg.EscalationTimeout)
				}
			}
		}
	}

	return matchRes, nil
}

func (m *BipartiteMatcher) handleNoRiders(orders []OrderRequest) (*MatchResult, error) {
	now := time.Now()
	isEscalated := false
	escalationReason := ""

	for _, o := range orders {
		if !o.CreatedAt.IsZero() && now.Sub(o.CreatedAt) >= m.cfg.EscalationTimeout {
			isEscalated = true
			escalationReason = fmt.Sprintf("Order %s pending assignment for >= %v with no candidate riders", o.ID, m.cfg.EscalationTimeout)
			break
		}
	}

	unassigned := make([]string, 0, len(orders))
	for _, o := range orders {
		unassigned = append(unassigned, o.ID)
	}

	return &MatchResult{
		SolverResult: &SolverResult{
			Assignments:      []OptimalAssignment{},
			UnassignedOrders: unassigned,
		},
		NeedsReevaluation: true,
		RetryAfter:        m.cfg.ReevaluationInterval,
		Escalated:         isEscalated,
		EscalationReason:  escalationReason,
	}, ErrNoRidersAvailable
}
