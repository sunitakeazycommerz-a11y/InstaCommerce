package optimizer

import (
	"math"
	"testing"
)

func TestHaversineDistance_SameLocation(t *testing.T) {
	p := Position{Lat: 12.9716, Lng: 77.5946}
	dist := HaversineDistance(p, p)
	if dist != 0.0 {
		t.Fatalf("expected distance between same position to be 0, got %f", dist)
	}
}

func TestHaversineDistance_KnownCoordinates(t *testing.T) {
	// Bangalore Majestic to Indiranagar (~7.5 km)
	p1 := Position{Lat: 12.9784, Lng: 77.5695}
	p2 := Position{Lat: 12.9784, Lng: 77.6408}

	dist := HaversineDistance(p1, p2)
	if dist < 7.0 || dist > 8.5 {
		t.Fatalf("expected distance between 7.0 and 8.5 km, got %f", dist)
	}
}

func TestEstimateETAMinutes_SpeedZeroOrNegativeReturnsInf(t *testing.T) {
	etaZero := EstimateETAMinutes(5.0, 0, 1.0)
	if !math.IsInf(etaZero, 1) {
		t.Fatalf("expected +Inf for speed 0, got %f", etaZero)
	}

	etaNeg := EstimateETAMinutes(5.0, -10, 1.0)
	if !math.IsInf(etaNeg, 1) {
		t.Fatalf("expected +Inf for speed < 0, got %f", etaNeg)
	}
}

func TestEstimateETAMinutes_CalculationWithTraffic(t *testing.T) {
	// 10 km at 60 km/h with 1.0 factor => 10 minutes
	eta := EstimateETAMinutes(10.0, 60.0, 1.0)
	if math.Abs(eta-10.0) > 1e-6 {
		t.Fatalf("expected 10.0 minutes, got %f", eta)
	}

	// 10 km at 60 km/h with 1.5 congestion factor => 15 minutes
	etaCongested := EstimateETAMinutes(10.0, 60.0, 1.5)
	if math.Abs(etaCongested-15.0) > 1e-6 {
		t.Fatalf("expected 15.0 minutes, got %f", etaCongested)
	}
}

func TestBatchCompatible_EmptyOrdersReturnsNil(t *testing.T) {
	groups := BatchCompatible(nil, 10.0)
	if groups != nil {
		t.Fatalf("expected nil for empty orders, got %v", groups)
	}
}

func TestBatchCompatible_NearbyOrdersBatched(t *testing.T) {
	orders := []OrderRequest{
		{
			ID:       "ord-1",
			Position: Position{Lat: 12.97160, Lng: 77.59460},
		},
		{
			ID:       "ord-2",
			Position: Position{Lat: 12.97170, Lng: 77.59470}, // ~15 meters away
		},
		{
			ID:       "ord-3",
			Position: Position{Lat: 13.05000, Lng: 77.70000}, // ~14 km away
		},
	}

	groups := BatchCompatible(orders, 5.0)
	if len(groups) != 2 {
		t.Fatalf("expected 2 groups, got %d: %v", len(groups), groups)
	}

	// First group should contain orders 0 and 1
	if len(groups[0]) != 2 || groups[0][0] != 0 || groups[0][1] != 1 {
		t.Fatalf("expected group 0 to contain [0, 1], got %v", groups[0])
	}

	// Second group should contain order 2
	if len(groups[1]) != 1 || groups[1][0] != 2 {
		t.Fatalf("expected group 1 to contain [2], got %v", groups[1])
	}
}
