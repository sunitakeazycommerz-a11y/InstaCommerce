package optimizer

import (
	"errors"
	"math"
)

const earthRadiusKm = 6371.0

// Position represents a WGS-84 geographic coordinate.
type Position struct {
	Lat float64 `json:"lat"`
	Lng float64 `json:"lng"`
}

// ValidatePosition verifies that a geographic coordinate is finite and within WGS-84 bounds.
func ValidatePosition(p Position) error {
	if math.IsNaN(p.Lat) || math.IsInf(p.Lat, 0) || p.Lat < -90 || p.Lat > 90 {
		return errors.New("lat must be a finite number between -90 and 90")
	}
	if math.IsNaN(p.Lng) || math.IsInf(p.Lng, 0) || p.Lng < -180 || p.Lng > 180 {
		return errors.New("lng must be a finite number between -180 and 180")
	}
	return nil
}

func clamp(val, min, max float64) float64 {
	if val < min {
		return min
	}
	if val > max {
		return max
	}
	return val
}

// HaversineDistance returns the great-circle distance in kilometres between a and b.
func HaversineDistance(a, b Position) float64 {
	dLat := degToRad(b.Lat - a.Lat)
	dLng := degToRad(b.Lng - a.Lng)
	lat1 := degToRad(a.Lat)
	lat2 := degToRad(b.Lat)

	sinDLat := math.Sin(dLat / 2)
	sinDLng := math.Sin(dLng / 2)
	h := clamp(sinDLat*sinDLat+math.Cos(lat1)*math.Cos(lat2)*sinDLng*sinDLng, 0.0, 1.0)
	return 2 * earthRadiusKm * math.Asin(math.Sqrt(h))
}

// EstimateETAMinutes returns estimated travel time in minutes, or +Inf if speedKmh <= 0.
func EstimateETAMinutes(distanceKm float64, speedKmh float64, trafficFactor float64) float64 {
	if speedKmh <= 0 {
		return math.Inf(1)
	}
	if trafficFactor <= 0 {
		trafficFactor = 1.0
	}
	return (distanceKm / speedKmh) * 60.0 * trafficFactor
}

// BatchCompatible groups order indices eligible for single-trip batching using
// the default detour distance cap.
func BatchCompatible(orders []OrderRequest, maxExtraMinutes float64) [][]int {
	return BatchCompatibleWithDetour(orders, maxExtraMinutes, DefaultMaxBatchDetourKm)
}

// BatchCompatibleWithDetour groups orders that share a co-located dark store,
// stay within 1 km pairwise / maxExtraMinutes ETA delta, and do not exceed
// maxDetourKm cumulative dropoff detour.
func BatchCompatibleWithDetour(orders []OrderRequest, maxExtraMinutes, maxDetourKm float64) [][]int {
	n := len(orders)
	if n == 0 {
		return nil
	}
	if maxDetourKm <= 0 {
		maxDetourKm = DefaultMaxBatchDetourKm
	}

	used := make([]bool, n)
	var groups [][]int

	for i := 0; i < n; i++ {
		if used[i] {
			continue
		}
		group := []int{i}
		used[i] = true

		for j := i + 1; j < n; j++ {
			if used[j] {
				continue
			}
			if canJoinBatch(orders, group, j, maxExtraMinutes, maxDetourKm) {
				group = append(group, j)
				used[j] = true
			}
		}
		groups = append(groups, group)
	}
	return groups
}

func canJoinBatch(orders []OrderRequest, group []int, candidate int, maxExtraMinutes, maxDetourKm float64) bool {
	cand := orders[candidate]
	for _, idx := range group {
		existing := orders[idx]
		if !storesColocated(existing, cand) {
			return false
		}
		dist := HaversineDistance(existing.Position, cand.Position)
		eta := EstimateETAMinutes(dist, 20.0, 1.0)
		if dist > 1.0 || eta > maxExtraMinutes {
			return false
		}
	}

	chainDist := 0.0
	prev := orders[group[0]].Position
	for _, idx := range group[1:] {
		cur := orders[idx].Position
		chainDist += HaversineDistance(prev, cur)
		prev = cur
	}
	chainDist += HaversineDistance(prev, cand.Position)
	return chainDist <= maxDetourKm
}

func storesColocated(a, b OrderRequest) bool {
	if a.StoreID != "" && b.StoreID != "" && a.StoreID != b.StoreID {
		return false
	}
	if a.StorePosition != nil && b.StorePosition != nil {
		return HaversineDistance(*a.StorePosition, *b.StorePosition) <= DefaultMaxStoreColocationKm
	}
	return true
}

func degToRad(deg float64) float64 {
	return deg * math.Pi / 180.0
}
