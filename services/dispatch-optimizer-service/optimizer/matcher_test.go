package optimizer

import (
	"context"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestBipartiteMatcher_EmptyRiderPoolReturnsErrNoRidersAvailable(t *testing.T) {
	matcher := NewBipartiteMatcher(nil, nil)

	orders := []OrderRequest{
		{
			ID:       "order-101",
			Position: Position{Lat: 12.9716, Lng: 77.5946},
		},
	}

	// Empty rider pool must NOT panic on nil matrix, but safely return ErrNoRidersAvailable (Issue #576)
	result, err := matcher.Match(context.Background(), []RiderState{}, orders)
	require.Error(t, err)
	assert.ErrorIs(t, err, ErrNoRidersAvailable)
	require.NotNil(t, result)
	assert.True(t, result.NeedsReevaluation)
	assert.Equal(t, 30*time.Second, result.RetryAfter)
	assert.Contains(t, result.UnassignedOrders, "order-101")
	assert.Empty(t, result.Assignments)
}

func TestBipartiteMatcher_RidersOutsideRadiusTreatedAsUnavailable(t *testing.T) {
	matcher := NewBipartiteMatcher(&MatcherConfig{
		MaxDispatchRadiusKm: 5.0,
	}, nil)

	// Bangalore order
	orders := []OrderRequest{
		{
			ID:       "order-blr",
			Position: Position{Lat: 12.9716, Lng: 77.5946},
		},
	}

	// Rider is 25 km away in Hosur area
	riders := []RiderState{
		{
			ID:          "rider-far",
			Position:    Position{Lat: 12.7409, Lng: 77.8253},
			IsAvailable: true,
		},
	}

	result, err := matcher.Match(context.Background(), riders, orders)
	require.Error(t, err)
	assert.ErrorIs(t, err, ErrNoRidersAvailable)
	require.NotNil(t, result)
	assert.True(t, result.NeedsReevaluation)
}

func TestBipartiteMatcher_SuccessfulMatching(t *testing.T) {
	matcher := NewBipartiteMatcher(nil, nil)

	orders := []OrderRequest{
		{
			ID:        "order-1",
			Position:  Position{Lat: 12.9716, Lng: 77.5946},
			ItemCount: 2,
			Weight:    1.5,
		},
	}

	riders := []RiderState{
		{
			ID:             "rider-nearby",
			Position:       Position{Lat: 12.9730, Lng: 77.5960}, // ~200m away
			IsAvailable:    true,
			AvgSpeedKmh:    25.0,
			BatteryPercent: 85.0,
		},
	}

	result, err := matcher.Match(context.Background(), riders, orders)
	require.NoError(t, err)
	require.NotNil(t, result)
	assert.False(t, result.NeedsReevaluation)
	assert.Len(t, result.Assignments, 1)
	assert.Equal(t, "rider-nearby", result.Assignments[0].RiderID)
	assert.Contains(t, result.Assignments[0].OrderIDs, "order-1")
}

func TestBipartiteMatcher_EscalationAfterTimeout(t *testing.T) {
	matcher := NewBipartiteMatcher(&MatcherConfig{
		EscalationTimeout: 3 * time.Minute,
	}, nil)

	oldOrder := OrderRequest{
		ID:        "order-delayed",
		Position:  Position{Lat: 12.9716, Lng: 77.5946},
		CreatedAt: time.Now().Add(-4 * time.Minute), // 4 minutes ago (> 3m timeout)
	}

	result, err := matcher.Match(context.Background(), []RiderState{}, []OrderRequest{oldOrder})
	require.ErrorIs(t, err, ErrNoRidersAvailable)
	require.NotNil(t, result)
	assert.True(t, result.Escalated)
	assert.Contains(t, result.EscalationReason, "pending assignment for >=")
}

func TestBatteryConstraint_FilterLowBatteryRidersOnLongDistance(t *testing.T) {
	checker := NewConstraintChecker(SolverConfig{})

	order := OrderRequest{
		ID:       "order-long-dist",
		Position: Position{Lat: 12.9716, Lng: 77.5946},
	}

	// Rider is ~4.2 km away (> 3.0 km) on electric scooter with 18% battery (< 20%)
	lowBatteryEVRider := RiderState{
		ID:              "rider-ev-low-batt",
		Position:        Position{Lat: 13.0100, Lng: 77.5946},
		VehicleType:     "electric_scooter",
		BatteryPercent:  18.0,
		IsAvailable:     true,
		TotalDeliveries: 100,
	}

	ok, violations := checker.CheckAll(lowBatteryEVRider, order)
	assert.False(t, ok, "Low battery EV rider on >3km delivery must be rejected")
	require.NotEmpty(t, violations)
	assert.Equal(t, "battery", violations[0].Constraint)
	assert.Contains(t, violations[0].Reason, "below 20.0% threshold")
	assert.Contains(t, violations[0].Reason, "battery swap station")

	// Same distance, but with 25% battery (> 20%)
	healthyBatteryEVRider := RiderState{
		ID:              "rider-ev-good-batt",
		Position:        Position{Lat: 13.0100, Lng: 77.5946},
		VehicleType:     "electric_scooter",
		BatteryPercent:  25.0,
		IsAvailable:     true,
		TotalDeliveries: 100,
	}

	okGood, violationsGood := checker.CheckAll(healthyBatteryEVRider, order)
	assert.True(t, okGood, "Healthy battery EV rider must be accepted")
	assert.Empty(t, violationsGood)

	// Non-electric bicycle and petrol scooter with 0 battery_percent must not be rejected as low-battery EVs
	for _, vType := range []string{"bicycle", "scooter", "motorcycle"} {
		nonEV := RiderState{
			ID:              "rider-" + vType,
			Position:        Position{Lat: 12.9720, Lng: 77.5950},
			VehicleType:     vType,
			BatteryPercent:  0.0,
			IsAvailable:     true,
			TotalDeliveries: 100,
		}
		ok, viol := checker.CheckAll(nonEV, order)
		assert.True(t, ok, "non-EV %s must not fail battery constraint: %+v", vType, viol)
	}

	// E-bike variants with low battery must be rejected by battery constraint
	for _, evType := range []string{"e-bike", "ebike", "electric_bicycle"} {
		lowEBike := RiderState{
			ID:              "rider-" + evType,
			Position:        Position{Lat: 12.9720, Lng: 77.5950},
			VehicleType:     evType,
			BatteryPercent:  10.0,
			IsAvailable:     true,
			TotalDeliveries: 100,
		}
		ok, viol := checker.CheckAll(lowEBike, order)
		assert.False(t, ok, "low battery %s should fail battery check", evType)
		require.NotEmpty(t, viol)
		assert.Equal(t, "battery", viol[0].Constraint)
	}
}

func TestSolver_ActiveOrdersCapacityAndWeightZeroHandling(t *testing.T) {
	cfg := SolverConfig{
		MaxOrdersPerRider:  2,
		WeightDeliveryTime: 1.0,
		WeightIdleTime:     0.0,
		WeightSLABreach:    0.0,
	}
	solver := NewSolver(cfg)
	assert.Equal(t, 1.0, solver.cfg.WeightDeliveryTime)
	assert.Equal(t, 0.0, solver.cfg.WeightIdleTime, "explicit zero weight must be preserved")
	assert.Equal(t, 0.0, solver.cfg.WeightSLABreach, "explicit zero weight must be preserved")

	riders := []RiderState{
		{
			ID:              "rider-busy",
			Position:        Position{Lat: 12.9716, Lng: 77.5946},
			ActiveOrders:    1, // already carrying 1 order -> can take at most 1 more (MaxOrdersPerRider=2)
			IsAvailable:     true,
			TotalDeliveries: 100,
		},
	}
	orders := []OrderRequest{
		{ID: "ord-1", Position: Position{Lat: 12.9717, Lng: 77.5947}},
		{ID: "ord-2", Position: Position{Lat: 12.9718, Lng: 77.5948}},
	}

	res, err := solver.Solve(context.Background(), riders, orders)
	require.NoError(t, err)
	require.Len(t, res.Assignments, 1)
	assert.Len(t, res.Assignments[0].OrderIDs, 1, "rider with ActiveOrders=1 and MaxOrdersPerRider=2 must only receive 1 new order")
	assert.Len(t, res.UnassignedOrders, 1)
}
