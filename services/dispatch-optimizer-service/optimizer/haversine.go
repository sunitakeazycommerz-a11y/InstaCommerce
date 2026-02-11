package optimizer

import "math"

// earthRadiusKm is the mean radius of the Earth in kilometres.
const earthRadiusKm = 6371.0

// Position represents a WGS-84 geographic coordinate.
type Position struct {
	Lat float64 `json:"lat"`
	Lng float64 `json:"lng"`
}

// HaversineDistance returns the great-circle distance in kilometres between two
// geographic positions using the Haversine formula.
func HaversineDistance(a, b Position) float64 {
	dLat := degToRad(b.Lat - a.Lat)
	dLng := degToRad(b.Lng - a.Lng)
	lat1 := degToRad(a.Lat)
	lat2 := degToRad(b.Lat)

	sinDLat := math.Sin(dLat / 2)
	sinDLng := math.Sin(dLng / 2)
	h := sinDLat*sinDLat + math.Cos(lat1)*math.Cos(lat2)*sinDLng*sinDLng
	return 2 * earthRadiusKm * math.Asin(math.Sqrt(h))
}

// EstimateETAMinutes returns the estimated travel time in minutes given a
// distance in kilometres, a rider speed in km/h and a multiplicative traffic
// factor (1.0 = free-flow, >1.0 = congested).
//
// If speedKmh is ≤ 0 the function returns +Inf to signal an impossible trip.
func EstimateETAMinutes(distanceKm float64, speedKmh float64, trafficFactor float64) float64 {
	if speedKmh <= 0 {
		return math.Inf(1)
	}
	if trafficFactor <= 0 {
		trafficFactor = 1.0
	}
	return (distanceKm / speedKmh) * 60.0 * trafficFactor
}

// BatchCompatible groups order indices that can be served in a single trip
// without adding more than maxExtraMinutes to any individual order's ETA.
// Orders are considered batch-compatible when they are within 1 km of each
// other. The returned slices contain indices into the supplied orders slice.
func BatchCompatible(orders []OrderRequest, maxExtraMinutes float64) [][]int {
	n := len(orders)
	if n == 0 {
		return nil
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
			compatible := true
			for _, idx := range group {
				dist := HaversineDistance(orders[idx].Position, orders[j].Position)
				// Assume a conservative 20 km/h average speed for ETA impact.
				eta := EstimateETAMinutes(dist, 20.0, 1.0)
				if dist > 1.0 || eta > maxExtraMinutes {
					compatible = false
					break
				}
			}
			if compatible {
				group = append(group, j)
				used[j] = true
			}
		}
		groups = append(groups, group)
	}
	return groups
}

// degToRad converts degrees to radians.
func degToRad(deg float64) float64 {
	return deg * math.Pi / 180.0
}
