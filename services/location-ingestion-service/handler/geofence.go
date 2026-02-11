package handler

import (
	"fmt"
	"math"
	"sync"
	"time"
)

// GeofenceEvent is emitted when a rider enters or exits a monitored zone.
type GeofenceEvent struct {
	RiderID   string
	EventType string // ENTERED_STORE, EXITED_ZONE, NEAR_DELIVERY
	ZoneID    string
	H3Index   string
	Timestamp time.Time
}

// ZoneType classifies a geofenced area.
type ZoneType string

const (
	// ZoneStore represents a pickup store or restaurant.
	ZoneStore ZoneType = "store"
	// ZoneDelivery represents a customer delivery area.
	ZoneDelivery ZoneType = "delivery"
	// ZoneRestricted represents a no-go area.
	ZoneRestricted ZoneType = "restricted"
)

// Zone defines a circular geofence boundary.
type Zone struct {
	ID       string
	Type     ZoneType
	CenterLat float64
	CenterLng float64
	RadiusKm  float64 // boundary radius in kilometres
}

// GeofenceChecker performs H3-based hexagonal geofence detection for rider
// location updates. It detects store arrivals, delivery-zone proximity, and
// restricted-area incursions.
type GeofenceChecker struct {
	mu         sync.RWMutex
	zones      map[string]Zone // zone_id → boundary
	resolution int             // H3 resolution (9 ≈ 174 m hexagons)
}

// NewGeofenceChecker creates a GeofenceChecker with the given H3 resolution.
// A resolution of 9 produces ~174 m hexagons and is the recommended default
// for urban delivery geofencing.
func NewGeofenceChecker(resolution int) *GeofenceChecker {
	return &GeofenceChecker{
		zones:      make(map[string]Zone),
		resolution: resolution,
	}
}

// AddZone registers a geofence zone. It is safe for concurrent use.
func (g *GeofenceChecker) AddZone(zone Zone) {
	g.mu.Lock()
	defer g.mu.Unlock()
	g.zones[zone.ID] = zone
}

// RemoveZone unregisters a geofence zone. It is safe for concurrent use.
func (g *GeofenceChecker) RemoveZone(zoneID string) {
	g.mu.Lock()
	defer g.mu.Unlock()
	delete(g.zones, zoneID)
}

// Check evaluates a LocationUpdate against all registered zones and returns
// any triggered GeofenceEvents. It is safe for concurrent use.
func (g *GeofenceChecker) Check(update LocationUpdate) []GeofenceEvent {
	g.mu.RLock()
	defer g.mu.RUnlock()

	h3Index := h3IndexStub(update.Lat, update.Lng, g.resolution)

	var events []GeofenceEvent
	for _, zone := range g.zones {
		dist := haversineKm(update.Lat, update.Lng, zone.CenterLat, zone.CenterLng)

		var eventType string
		switch {
		case zone.Type == ZoneStore && dist <= zone.RadiusKm:
			eventType = "ENTERED_STORE"
		case zone.Type == ZoneDelivery && dist <= zone.RadiusKm:
			eventType = "NEAR_DELIVERY"
		case zone.Type == ZoneRestricted && dist <= zone.RadiusKm:
			eventType = "ENTERED_RESTRICTED"
		case dist > zone.RadiusKm && dist <= zone.RadiusKm*1.5:
			// Rider just exited the zone boundary (with a small buffer).
			eventType = "EXITED_ZONE"
		default:
			continue
		}

		events = append(events, GeofenceEvent{
			RiderID:   update.RiderID,
			EventType: eventType,
			ZoneID:    zone.ID,
			H3Index:   h3Index,
			Timestamp: update.Timestamp,
		})
	}
	return events
}

// h3IndexStub generates a deterministic H3-like index string. In production,
// replace this with a call to the uber/h3-go library.
func h3IndexStub(lat, lng float64, resolution int) string {
	return fmt.Sprintf("h3-%d-%.5f-%.5f", resolution, lat, lng)
}

// earthRadiusKm is the mean radius of the Earth.
const earthRadiusKm = 6371.0

// haversineKm computes the great-circle distance in kilometres between two
// geographic coordinates.
func haversineKm(lat1, lng1, lat2, lng2 float64) float64 {
	dLat := degToRad(lat2 - lat1)
	dLng := degToRad(lng2 - lng1)

	a := math.Sin(dLat/2)*math.Sin(dLat/2) +
		math.Cos(degToRad(lat1))*math.Cos(degToRad(lat2))*
			math.Sin(dLng/2)*math.Sin(dLng/2)

	c := 2 * math.Atan2(math.Sqrt(a), math.Sqrt(1-a))
	return earthRadiusKm * c
}

// degToRad converts degrees to radians.
func degToRad(deg float64) float64 {
	return deg * math.Pi / 180
}
