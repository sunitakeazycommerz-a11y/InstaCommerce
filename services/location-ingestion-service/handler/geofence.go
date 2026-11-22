package handler

import (
	"context"
	"fmt"
	"math"
	"sync"
	"time"
)

const (
	// DefaultGeofenceTopic is the standard Kafka topic for rider geofence transitions.
	DefaultGeofenceTopic = "rider.geofence.events"

	// DefaultHysteresisWindow is the suppression window preventing redundant geofence events.
	DefaultHysteresisWindow = 3 * time.Minute

	// DefaultStoreConsecutivePings is the number of consecutive GPS pings required inside a store boundary.
	DefaultStoreConsecutivePings = 3
)

// GeofenceEvent is emitted when a rider enters or exits a monitored zone.
type GeofenceEvent struct {
	RiderID   string    `json:"rider_id"`
	EventType string    `json:"event_type"` // ENTERED_STORE, EXITED_ZONE, NEAR_DELIVERY, ENTERED_RESTRICTED
	ZoneID    string    `json:"zone_id"`
	H3Index   string    `json:"h3_index"`
	Timestamp time.Time `json:"timestamp"`
}

// GeofenceEventPayload represents the domain payload of a geofence transition.
type GeofenceEventPayload struct {
	RiderID   string    `json:"rider_id"`
	ZoneID    string    `json:"zone_id"`
	EventType string    `json:"event_type"`
	H3Index   string    `json:"h3_index"`
	Timestamp time.Time `json:"timestamp"`
	Lat       float64   `json:"lat"`
	Lng       float64   `json:"lng"`
}

// GeofenceEventEnvelope wraps a GeofenceEvent according to canonical EventEnvelope.v1.json.
type GeofenceEventEnvelope struct {
	ID            string               `json:"id"`
	EventID       string               `json:"eventId"`
	EventType     string               `json:"eventType"`
	AggregateType string               `json:"aggregateType"`
	AggregateID   string               `json:"aggregateId"`
	EventTime     string               `json:"eventTime"`
	SchemaVersion string               `json:"schemaVersion"`
	SourceService string               `json:"source_service"`
	CorrelationID string               `json:"correlation_id,omitempty"`
	Payload       GeofenceEventPayload `json:"payload"`
}

// ToEnvelope converts a GeofenceEvent and GPS coordinates to a canonical EventEnvelope.
func (e GeofenceEvent) ToEnvelope(lat, lng float64) GeofenceEventEnvelope {
	ts := e.Timestamp.UTC()
	if ts.IsZero() {
		ts = time.Now().UTC()
	}
	id := fmt.Sprintf("geo_%s_%d", e.RiderID, ts.UnixNano())
	return GeofenceEventEnvelope{
		ID:            id,
		EventID:       id,
		EventType:     "RiderGeofenceTransition",
		AggregateType: "RiderGeofence",
		AggregateID:   e.RiderID,
		EventTime:     ts.Format(time.RFC3339Nano),
		SchemaVersion: "v1",
		SourceService: "location-ingestion-service",
		Payload: GeofenceEventPayload{
			RiderID:   e.RiderID,
			ZoneID:    e.ZoneID,
			EventType: e.EventType,
			H3Index:   e.H3Index,
			Timestamp: ts,
			Lat:       lat,
			Lng:       lng,
		},
	}
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

// Zone defines a circular geofence boundary with debounce and hysteresis options.
type Zone struct {
	ID                  string
	Type                ZoneType
	CenterLat           float64
	CenterLng           float64
	RadiusKm            float64       // boundary radius in kilometres
	MinConsecutivePings int           // consecutive GPS pings inside boundary required before confirming transition
	HysteresisWindow    time.Duration // minimum duration before duplicate transition can be emitted
}

// GeofenceDeduplicator defines interface for deduplicating geofence events (e.g. in Redis).
type GeofenceDeduplicator interface {
	TryAcquire(ctx context.Context, key string, ttl time.Duration) bool
}

// InMemoryGeofenceDeduplicator provides in-memory deduplication fallback.
type InMemoryGeofenceDeduplicator struct {
	mu      sync.Mutex
	entries map[string]time.Time
}

func NewInMemoryGeofenceDeduplicator() *InMemoryGeofenceDeduplicator {
	return &InMemoryGeofenceDeduplicator{
		entries: make(map[string]time.Time),
	}
}

func (d *InMemoryGeofenceDeduplicator) TryAcquire(ctx context.Context, key string, ttl time.Duration) bool {
	d.mu.Lock()
	defer d.mu.Unlock()

	now := time.Now()
	if exp, exists := d.entries[key]; exists && now.Before(exp) {
		return false
	}

	if len(d.entries) > 1000 {
		for k, exp := range d.entries {
			if !now.Before(exp) {
				delete(d.entries, k)
			}
		}
	}

	d.entries[key] = now.Add(ttl)
	return true
}

type riderZoneKey struct {
	riderID string
	zoneID  string
}

type riderZoneTracking struct {
	consecutiveInside int
	lastEventTime     time.Time
	lastEventType     string
	inside            bool
}

// SpatialIndexer maps geographic coordinates and H3 resolution to a spatial grid cell index.
type SpatialIndexer interface {
	Index(lat, lng float64, resolution int) string
}

// SpatialIndexerFunc adapts a function to SpatialIndexer.
type SpatialIndexerFunc func(lat, lng float64, resolution int) string

// Index computes the spatial grid cell identifier for lat/lng at resolution.
func (f SpatialIndexerFunc) Index(lat, lng float64, resolution int) string {
	return f(lat, lng, resolution)
}

// GeofenceChecker performs hexagonal grid geofence detection for rider location updates.
type GeofenceChecker struct {
	mu                   sync.RWMutex
	zones                map[string]Zone
	resolution           int
	indexer              SpatialIndexer
	deduplicator         GeofenceDeduplicator
	tracking             map[riderZoneKey]*riderZoneTracking
	lastPingTime         map[string]time.Time
	defaultHysteresis    time.Duration
	defaultRequiredPings int
}

// NewGeofenceChecker creates a GeofenceChecker with the given H3 resolution.
func NewGeofenceChecker(resolution int) *GeofenceChecker {
	return &GeofenceChecker{
		zones:                make(map[string]Zone),
		resolution:           resolution,
		indexer:              SpatialIndexerFunc(EncodeSpatialGridIndex),
		deduplicator:         NewInMemoryGeofenceDeduplicator(),
		tracking:             make(map[riderZoneKey]*riderZoneTracking),
		lastPingTime:         make(map[string]time.Time),
		defaultHysteresis:    DefaultHysteresisWindow,
		defaultRequiredPings: 1,
	}
}

// SetSpatialIndexer configures a custom spatial indexer implementation.
func (g *GeofenceChecker) SetSpatialIndexer(indexer SpatialIndexer) {
	g.mu.Lock()
	defer g.mu.Unlock()
	if indexer == nil {
		g.indexer = SpatialIndexerFunc(EncodeSpatialGridIndex)
		return
	}
	g.indexer = indexer
}

// SetDeduplicator configures an external deduplicator (e.g. Redis).
func (g *GeofenceChecker) SetDeduplicator(d GeofenceDeduplicator) {
	g.mu.Lock()
	defer g.mu.Unlock()
	g.deduplicator = d
}

// SetDefaultRequiredConsecutivePings sets the default consecutive pings requirement.
func (g *GeofenceChecker) SetDefaultRequiredConsecutivePings(pings int) {
	g.mu.Lock()
	defer g.mu.Unlock()
	g.defaultRequiredPings = pings
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

// Check evaluates a LocationUpdate against registered zones with hysteresis and debounce.
func (g *GeofenceChecker) Check(update LocationUpdate) []GeofenceEvent {
	g.mu.Lock()
	defer g.mu.Unlock()

	for _, zone := range g.zones {
		if track, exists := g.tracking[riderZoneKey{riderID: update.RiderID, zoneID: zone.ID}]; exists {
			if !track.lastEventTime.IsZero() && !update.Timestamp.After(track.lastEventTime) {
				return nil
			}
		}
	}
	if !update.Timestamp.IsZero() {
		if g.lastPingTime == nil {
			g.lastPingTime = make(map[string]time.Time)
		}
		if lastTs, exists := g.lastPingTime[update.RiderID]; exists && update.Timestamp.Before(lastTs) {
			return nil
		}
		g.lastPingTime[update.RiderID] = update.Timestamp
	}

	indexer := g.indexer
	if indexer == nil {
		indexer = SpatialIndexerFunc(EncodeSpatialGridIndex)
	}
	h3Index := indexer.Index(update.Lat, update.Lng, g.resolution)
	var events []GeofenceEvent

	for _, zone := range g.zones {
		dist := haversineKm(update.Lat, update.Lng, zone.CenterLat, zone.CenterLng)
		key := riderZoneKey{riderID: update.RiderID, zoneID: zone.ID}
		track, exists := g.tracking[key]
		if !exists {
			track = &riderZoneTracking{}
			g.tracking[key] = track
		}

		hysteresis := zone.HysteresisWindow
		if hysteresis <= 0 {
			hysteresis = g.defaultHysteresis
		}

		requiredPings := zone.MinConsecutivePings
		if requiredPings <= 0 {
			requiredPings = g.defaultRequiredPings
		}

		var eventType string
		isInside := false

		switch {
		case zone.Type == ZoneStore && dist <= zone.RadiusKm:
			eventType = "ENTERED_STORE"
			isInside = true
		case zone.Type == ZoneDelivery && dist <= zone.RadiusKm:
			eventType = "NEAR_DELIVERY"
			isInside = true
		case zone.Type == ZoneRestricted && dist <= zone.RadiusKm:
			eventType = "ENTERED_RESTRICTED"
			isInside = true
		case dist > zone.RadiusKm:
			if track.inside {
				eventType = "EXITED_ZONE"
			} else {
				track.consecutiveInside = 0
				continue
			}
		default:
			track.consecutiveInside = 0
			track.inside = false
			continue
		}

		if isInside {
			track.consecutiveInside++
			if track.consecutiveInside < requiredPings {
				continue
			}

			if !track.lastEventTime.IsZero() && update.Timestamp.Sub(track.lastEventTime) < hysteresis && track.lastEventType == eventType {
				continue
			}

			redisKey := fmt.Sprintf("geofence:%s:%s", update.RiderID, zone.ID)
			if g.deduplicator != nil && !g.deduplicator.TryAcquire(context.Background(), redisKey, hysteresis) {
				continue
			}

			track.inside = true
			track.lastEventTime = update.Timestamp
			track.lastEventType = eventType

			events = append(events, GeofenceEvent{
				RiderID:   update.RiderID,
				EventType: eventType,
				ZoneID:    zone.ID,
				H3Index:   h3Index,
				Timestamp: update.Timestamp,
			})
		} else if eventType == "EXITED_ZONE" {
			track.consecutiveInside = 0
			if !track.inside {
				continue
			}

			if !track.lastEventTime.IsZero() && update.Timestamp.Sub(track.lastEventTime) < hysteresis && track.lastEventType == "EXITED_ZONE" {
				continue
			}

			track.inside = false
			track.lastEventTime = update.Timestamp
			track.lastEventType = "EXITED_ZONE"

			events = append(events, GeofenceEvent{
				RiderID:   update.RiderID,
				EventType: "EXITED_ZONE",
				ZoneID:    zone.ID,
				H3Index:   h3Index,
				Timestamp: update.Timestamp,
			})
		}
	}

	return events
}

const earthRadiusKm = 6371.0

func haversineKm(lat1, lng1, lat2, lng2 float64) float64 {
	dLat := degToRad(lat2 - lat1)
	dLng := degToRad(lng2 - lng1)

	a := math.Sin(dLat/2)*math.Sin(dLat/2) +
		math.Cos(degToRad(lat1))*math.Cos(degToRad(lat2))*
			math.Sin(dLng/2)*math.Sin(dLng/2)

	c := 2 * math.Atan2(math.Sqrt(a), math.Sqrt(1-a))
	return earthRadiusKm * c
}

func degToRad(deg float64) float64 {
	return deg * math.Pi / 180
}
