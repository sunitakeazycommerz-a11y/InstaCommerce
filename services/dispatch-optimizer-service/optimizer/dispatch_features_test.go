package optimizer

import (
	"context"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestBatchCompatibleWithDetour_StoreColocationAndDetourCap(t *testing.T) {
	base := Position{Lat: 12.9716, Lng: 77.5946}
	near1 := Position{Lat: 12.9746, Lng: 77.5946}  // ~0.33 km north
	near2 := Position{Lat: 12.9776, Lng: 77.5946}  // ~0.67 km from base
	zigzag := Position{Lat: 12.9746, Lng: 77.6020} // ~0.8 km east of near1

	tests := []struct {
		name        string
		orders      []OrderRequest
		maxDetourKm float64
		wantGroups  int
	}{
		{
			name: "co-located store orders within detour cap batch together",
			orders: []OrderRequest{
				{ID: "o1", StoreID: "store-a", StorePosition: &base, Position: base},
				{ID: "o2", StoreID: "store-a", StorePosition: &base, Position: near1},
			},
			maxDetourKm: 1.0,
			wantGroups:  1,
		},
		{
			name: "different store IDs are never batched together",
			orders: []OrderRequest{
				{ID: "o1", StoreID: "store-a", Position: base},
				{ID: "o2", StoreID: "store-b", Position: near1},
			},
			maxDetourKm: 1.5,
			wantGroups:  2,
		},
		{
			name: "nearby stores with distinct distant StorePositions stay separate",
			orders: []OrderRequest{
				{ID: "o1", StorePosition: &base, Position: base},
				{ID: "o2", StorePosition: &near2, Position: near1},
			},
			maxDetourKm: 1.5,
			wantGroups:  2,
		},
		{
			name: "chain detour exceeding maxDetourKm splits group",
			orders: []OrderRequest{
				{ID: "o1", StoreID: "store-a", Position: base},
				{ID: "o2", StoreID: "store-a", Position: near1},
				{ID: "o3", StoreID: "store-a", Position: zigzag},
			},
			maxDetourKm: 0.9,
			wantGroups:  2,
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			groups := BatchCompatibleWithDetour(tc.orders, 10.0, tc.maxDetourKm)
			assert.Len(t, groups, tc.wantGroups, "groups=%v", groups)
		})
	}
}

func TestConstraintChecker_BagCapacityAndColdChain(t *testing.T) {
	checker := NewConstraintChecker(SolverConfig{})

	tests := []struct {
		name           string
		rider          RiderState
		order          OrderRequest
		wantOK         bool
		wantConstraint string
	}{
		{
			name: "cumulative weight over scooter bag cap rejected",
			rider: RiderState{
				ID:                 "r1",
				VehicleType:        "scooter",
				IsAvailable:        true,
				TotalDeliveries:    100,
				CurrentBagWeightKg: 14.0,
				MaxBagWeightKg:     18.0,
			},
			order: OrderRequest{
				ID:     "o1",
				Weight: 5.0,
			},
			wantOK:         false,
			wantConstraint: "bag_capacity",
		},
		{
			name: "cumulative volume over bag cap rejected",
			rider: RiderState{
				ID:                     "r2",
				VehicleType:            "scooter",
				IsAvailable:            true,
				TotalDeliveries:        100,
				CurrentBagVolumeLiters: 38.0,
				MaxBagVolumeLiters:     45.0,
			},
			order: OrderRequest{
				ID:           "o2",
				VolumeLiters: 10.0,
			},
			wantOK:         false,
			wantConstraint: "bag_capacity",
		},
		{
			name: "cold chain order without thermal bag rejected",
			rider: RiderState{
				ID:              "r3",
				VehicleType:     "scooter",
				IsAvailable:     true,
				TotalDeliveries: 100,
				HasThermalBag:   false,
			},
			order: OrderRequest{
				ID:                "o3",
				RequiresColdChain: true,
				ColdChainTier:     "frozen",
			},
			wantOK:         false,
			wantConstraint: "cold_chain",
		},
		{
			name: "thermal bag rider accepts cold chain within limits",
			rider: RiderState{
				ID:              "r4",
				VehicleType:     "scooter",
				IsAvailable:     true,
				TotalDeliveries: 100,
				HasThermalBag:   true,
				MaxBagWeightKg:  15.0,
			},
			order: OrderRequest{
				ID:                "o4",
				RequiresColdChain: true,
				ColdChainTier:     "chilled",
				Weight:            4.0,
				VolumeLiters:      12.0,
			},
			wantOK: true,
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			ok, viols := checker.CheckAll(tc.rider, tc.order)
			assert.Equal(t, tc.wantOK, ok)
			if !tc.wantOK {
				require.NotEmpty(t, viols)
				assert.Equal(t, tc.wantConstraint, viols[0].Constraint)
			}
		})
	}
}

func TestSolver_CumulativeBagLimitsAcrossBatchedAndSwappedOrders(t *testing.T) {
	solver := NewSolver(SolverConfig{MaxOrdersPerRider: 3})
	riders := []RiderState{
		{
			ID:                 "r1",
			Position:           Position{Lat: 12.9716, Lng: 77.5946},
			IsAvailable:        true,
			TotalDeliveries:    100,
			MaxBagWeightKg:     10.0,
			MaxBagVolumeLiters: 30.0,
		},
	}
	orders := []OrderRequest{
		{ID: "o1", StoreID: "s1", Position: Position{Lat: 12.9717, Lng: 77.5947}, Weight: 6.0, VolumeLiters: 12.0},
		{ID: "o2", StoreID: "s1", Position: Position{Lat: 12.9718, Lng: 77.5948}, Weight: 6.0, VolumeLiters: 12.0},
	}

	res, err := solver.Solve(context.Background(), riders, orders)
	require.NoError(t, err)
	require.Len(t, res.Assignments, 1)
	assert.Len(t, res.Assignments[0].OrderIDs, 1, "rider cannot carry 12 kg with 10 kg bag cap")
	assert.Len(t, res.UnassignedOrders, 1)
}

func TestSolver_FrozenAndSLABreachPriorityAheadOfAmbient(t *testing.T) {
	solver := NewSolver(SolverConfig{MaxOrdersPerRider: 1})
	now := time.Now()
	riders := []RiderState{
		{
			ID:              "single-thermal-rider",
			Position:        Position{Lat: 12.9716, Lng: 77.5946},
			IsAvailable:     true,
			TotalDeliveries: 100,
			AvgSpeedKmh:     20.0,
			HasThermalBag:   true,
		},
	}
	orders := []OrderRequest{
		{
			ID:          "ambient-slightly-closer",
			Position:    Position{Lat: 12.9717, Lng: 77.5947},
			SLADeadline: now.Add(25 * time.Minute),
		},
		{
			ID:                "frozen-urgent",
			Position:          Position{Lat: 12.9740, Lng: 77.5960},
			RequiresColdChain: true,
			ColdChainTier:     "frozen",
			SLADeadline:       now.Add(8 * time.Minute),
		},
	}

	res, err := solver.Solve(context.Background(), riders, orders)
	require.NoError(t, err)
	require.Len(t, res.Assignments, 1)
	assert.Equal(t, []string{"frozen-urgent"}, res.Assignments[0].OrderIDs)
	assert.Equal(t, []string{"ambient-slightly-closer"}, res.UnassignedOrders)
}
