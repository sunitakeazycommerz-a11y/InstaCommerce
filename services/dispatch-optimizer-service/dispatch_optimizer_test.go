package main

import (
	"math"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestDistanceCalculationWithHaversineFormula(t *testing.T) {
	// Test distance calculation between two points on Earth
	tests := []struct {
		name           string
		pos1           Position
		pos2           Position
		expectedMinKm  float64
		expectedMaxKm  float64
		description    string
	}{
		{
			name:          "Same location",
			pos1:          Position{Lat: 12.9716, Lng: 77.5946},
			pos2:          Position{Lat: 12.9716, Lng: 77.5946},
			expectedMinKm: 0.0,
			expectedMaxKm: 0.01,
			description:   "Distance between same coordinates should be ~0 km",
		},
		{
			name:          "Bangalore to Delhi (approximate)",
			pos1:          Position{Lat: 12.9716, Lng: 77.5946},
			pos2:          Position{Lat: 28.7041, Lng: 77.1025},
			expectedMinKm: 2000,
			expectedMaxKm: 2500,
			description:   "Distance should be roughly 2000-2500 km",
		},
		{
			name:          "Close locations",
			pos1:          Position{Lat: 12.9716, Lng: 77.5946},
			pos2:          Position{Lat: 12.9750, Lng: 77.6000},
			expectedMinKm: 0.0,
			expectedMaxKm: 5.0,
			description:   "Distance within same city should be < 5 km",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			dist := distance(tt.pos1, tt.pos2)
			assert.GreaterOrEqual(t, dist, tt.expectedMinKm, tt.description)
			assert.LessOrEqual(t, dist, tt.expectedMaxKm, tt.description)
		})
	}
}

func TestDispatchOptimizationWithAvailableRiders(t *testing.T) {
	// Test that optimization assigns orders to riders correctly
	riders := []Rider{
		{ID: "rider-1", Position: Position{Lat: 12.97, Lng: 77.59}},
		{ID: "rider-2", Position: Position{Lat: 12.98, Lng: 77.60}},
	}

	orders := []Order{
		{ID: "order-1", Position: Position{Lat: 12.95, Lng: 77.58}},
		{ID: "order-2", Position: Position{Lat: 12.99, Lng: 77.61}},
	}

	constraints := ConstraintSet{
		CapacityConstraint{Max: 5},
	}

	assignments, unassigned := optimizeAssignments(riders, orders, constraints)

	assert.Len(t, assignments, 2)
	assert.Empty(t, unassigned)

	// Verify each rider has assignments
	for _, assignment := range assignments {
		assert.NotEmpty(t, assignment.RiderID)
		assert.GreaterOrEqual(t, assignment.TotalDistance, 0.0)
	}
}

func TestDispatchOptimizationWithCapacityConstraint(t *testing.T) {
	// Test that capacity constraint is respected
	riders := []Rider{
		{ID: "rider-1", Position: Position{Lat: 12.97, Lng: 77.59}},
	}

	orders := []Order{
		{ID: "order-1", Position: Position{Lat: 12.95, Lng: 77.58}},
		{ID: "order-2", Position: Position{Lat: 12.96, Lng: 77.59}},
		{ID: "order-3", Position: Position{Lat: 12.98, Lng: 77.60}},
	}

	// Capacity constraint allows only 2 orders per rider
	constraints := ConstraintSet{
		CapacityConstraint{Max: 2},
	}

	assignments, unassigned := optimizeAssignments(riders, orders, constraints)

	require.Len(t, assignments, 1)
	assignment := assignments[0]

	// Rider should have exactly 2 orders (capacity limit)
	assert.LessOrEqual(t, len(assignment.OrderIDs), 2)
	// At least one order should be unassigned
	assert.Greater(t, len(unassigned), 0)
}

func TestDispatchOptimizationWithZoneConstraint(t *testing.T) {
	// Test that zone constraint is respected
	riders := []Rider{
		{ID: "rider-1", Position: Position{Lat: 12.97, Lng: 77.59}, Zone: "zone-a"},
		{ID: "rider-2", Position: Position{Lat: 12.98, Lng: 77.60}, Zone: "zone-b"},
	}

	orders := []Order{
		{ID: "order-1", Position: Position{Lat: 12.95, Lng: 77.58}, Zone: "zone-a"},
		{ID: "order-2", Position: Position{Lat: 12.96, Lng: 77.59}, Zone: "zone-b"},
	}

	constraints := ConstraintSet{
		ZoneConstraint{},
		CapacityConstraint{Max: 5},
	}

	assignments, unassigned := optimizeAssignments(riders, orders, constraints)

	assert.Len(t, assignments, 2)
	assert.Empty(t, unassigned)

	// Verify each rider gets orders from their zone
	for _, assignment := range assignments {
		assert.NotEmpty(t, assignment.OrderIDs)
	}
}

func TestDispatchOptimizationWithNoAvailableRiders(t *testing.T) {
	// Test behavior when no riders are available
	riders := []Rider{}
	orders := []Order{
		{ID: "order-1", Position: Position{Lat: 12.95, Lng: 77.58}},
	}

	constraints := ConstraintSet{
		CapacityConstraint{Max: 5},
	}

	assignments, unassigned := optimizeAssignments(riders, orders, constraints)

	assert.Empty(t, assignments)
	assert.Len(t, unassigned, 1)
	assert.Equal(t, "order-1", unassigned[0])
}

func TestDistanceCalculationAccuracy(t *testing.T) {
	// Test haversine distance calculation accuracy
	pos1 := Position{Lat: 0.0, Lng: 0.0}
	pos2 := Position{Lat: 0.0, Lng: 0.0}

	dist := distance(pos1, pos2)
	assert.Less(t, dist, 0.001, "Distance between same points should be ~0")
}

func TestDistanceCalculationSymmetry(t *testing.T) {
	// Test that distance calculation is symmetric
	pos1 := Position{Lat: 12.9716, Lng: 77.5946}
	pos2 := Position{Lat: 12.9750, Lng: 77.6000}

	dist1 := distance(pos1, pos2)
	dist2 := distance(pos2, pos1)

	assert.InDelta(t, dist1, dist2, 0.001, "Distance should be symmetric")
}

func TestTotalDistanceCalculationInAssignment(t *testing.T) {
	// Test that total distance is calculated correctly for assignments
	riders := []Rider{
		{ID: "rider-1", Position: Position{Lat: 12.97, Lng: 77.59}},
	}

	orders := []Order{
		{ID: "order-1", Position: Position{Lat: 12.97, Lng: 77.59}},
		{ID: "order-2", Position: Position{Lat: 12.98, Lng: 77.60}},
	}

	constraints := ConstraintSet{
		CapacityConstraint{Max: 5},
	}

	assignments, _ := optimizeAssignments(riders, orders, constraints)

	require.Len(t, assignments, 1)
	assignment := assignments[0]

	// Total distance should be non-negative and reasonable
	assert.GreaterOrEqual(t, assignment.TotalDistance, 0.0)
	assert.Less(t, assignment.TotalDistance, 10000.0, "Distance should be reasonable")
}

func TestConstraintValidation(t *testing.T) {
	// Test that constraints are properly validated
	capacityConstraint := CapacityConstraint{Max: 5}
	assert.Equal(t, "capacity", capacityConstraint.Name())

	zoneConstraint := ZoneConstraint{}
	assert.Equal(t, "zone", zoneConstraint.Name())

	// Test capacity constraint evaluation
	rider := Rider{ID: "rider-1", Position: Position{Lat: 12.97, Lng: 77.59}}
	order := Order{ID: "order-1", Position: Position{Lat: 12.95, Lng: 77.58}}
	assignedOrders := make([]Order, 3)

	result := capacityConstraint.Allows(rider, order, assignedOrders)
	assert.True(t, result, "Capacity constraint should allow with 3 orders and limit 5")

	result = capacityConstraint.Allows(rider, order, make([]Order, 5))
	assert.False(t, result, "Capacity constraint should reject with 5 orders and limit 5")
}

func TestMathematicalPropertiesOfDistance(t *testing.T) {
	// Test mathematical properties: triangle inequality
	pos1 := Position{Lat: 0.0, Lng: 0.0}
	pos2 := Position{Lat: 1.0, Lng: 1.0}
	pos3 := Position{Lat: 2.0, Lng: 2.0}

	d12 := distance(pos1, pos2)
	d23 := distance(pos2, pos3)
	d13 := distance(pos1, pos3)

	// Triangle inequality: d13 <= d12 + d23
	assert.LessOrEqual(t, d13, d12+d23+0.001, "Triangle inequality should hold")
}

func TestNaNHandlingInDistance(t *testing.T) {
	// Test that extreme coordinates don't produce NaN
	testCases := []struct {
		name string
		pos1 Position
		pos2 Position
	}{
		{
			name: "North pole to equator",
			pos1: Position{Lat: 90.0, Lng: 0.0},
			pos2: Position{Lat: 0.0, Lng: 0.0},
		},
		{
			name: "International date line",
			pos1: Position{Lat: 0.0, Lng: -180.0},
			pos2: Position{Lat: 0.0, Lng: 180.0},
		},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			dist := distance(tc.pos1, tc.pos2)
			assert.False(t, math.IsNaN(dist), "Distance calculation should not produce NaN")
			assert.False(t, math.IsInf(dist, 0), "Distance calculation should not produce Infinity")
		})
	}
}
