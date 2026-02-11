// Package store provides persistence backends for the location-ingestion
// service. The primary implementation stores the latest rider position in
// Redis so that dispatch-optimizer and routing-eta services can query it
// with sub-millisecond latency.
package store

import (
	"context"
	"encoding/json"
	"fmt"
	"math"
	"time"

	"github.com/redis/go-redis/v9"
)

// LocationUpdate mirrors handler.LocationUpdate and is duplicated here to
// avoid a circular import. In a larger codebase this would live in a shared
// domain package.
type LocationUpdate struct {
	RiderID    string    `json:"rider_id"`
	Lat        float64   `json:"lat"`
	Lng        float64   `json:"lng"`
	Accuracy   float64   `json:"accuracy_meters"`
	Speed      float64   `json:"speed_kmh"`
	Heading    float64   `json:"heading_degrees"`
	Timestamp  time.Time `json:"timestamp"`
	BatteryPct float64   `json:"battery_pct"`
}

// LatestPositionStore persists the most recent GPS position for each rider in
// Redis. Keys follow the pattern rider:{rider_id}:location and expire after
// ttl to automatically mark riders as offline when no updates arrive.
//
// Used by dispatch-optimizer and routing-eta services for real-time queries.
type LatestPositionStore struct {
	client *redis.Client
	ttl    time.Duration // default 5 min; if no update, rider is considered offline
}

// NewLatestPositionStore creates a store backed by the given Redis client.
// ttl controls how long a position is retained before the rider is considered
// offline (recommended: 5 minutes).
func NewLatestPositionStore(client *redis.Client, ttl time.Duration) (*LatestPositionStore, error) {
	if client == nil {
		return nil, fmt.Errorf("store: redis client must not be nil")
	}
	if ttl <= 0 {
		ttl = 5 * time.Minute
	}
	return &LatestPositionStore{client: client, ttl: ttl}, nil
}

// keyFor returns the Redis key for a rider's latest position.
func keyFor(riderID string) string {
	return "rider:" + riderID + ":location"
}

// Update stores (or overwrites) the latest position for a rider. The key's
// TTL is refreshed on every call so that active riders remain visible.
func (s *LatestPositionStore) Update(ctx context.Context, update LocationUpdate) error {
	data, err := json.Marshal(&update)
	if err != nil {
		return fmt.Errorf("store: marshal position: %w", err)
	}

	key := keyFor(update.RiderID)
	if err := s.client.Set(ctx, key, data, s.ttl).Err(); err != nil {
		return fmt.Errorf("store: set position for rider %s: %w", update.RiderID, err)
	}
	return nil
}

// Get retrieves the latest known position for riderID. Returns nil if the
// rider is not found (key expired or never set).
func (s *LatestPositionStore) Get(ctx context.Context, riderID string) (*LocationUpdate, error) {
	data, err := s.client.Get(ctx, keyFor(riderID)).Bytes()
	if err != nil {
		if err == redis.Nil {
			return nil, nil
		}
		return nil, fmt.Errorf("store: get position for rider %s: %w", riderID, err)
	}

	var update LocationUpdate
	if err := json.Unmarshal(data, &update); err != nil {
		return nil, fmt.Errorf("store: unmarshal position for rider %s: %w", riderID, err)
	}
	return &update, nil
}

// GetNearby returns all riders whose latest known position is within radiusKm
// of the given coordinate. This implementation performs a full scan of matching
// keys and is suitable for moderate fleet sizes. For large fleets, use Redis
// GEO commands or a spatial index.
func (s *LatestPositionStore) GetNearby(ctx context.Context, lat, lng, radiusKm float64) ([]LocationUpdate, error) {
	var cursor uint64
	var results []LocationUpdate

	for {
		keys, nextCursor, err := s.client.Scan(ctx, cursor, "rider:*:location", 100).Result()
		if err != nil {
			return nil, fmt.Errorf("store: scan rider keys: %w", err)
		}

		for _, key := range keys {
			data, err := s.client.Get(ctx, key).Bytes()
			if err != nil {
				if err == redis.Nil {
					continue
				}
				return nil, fmt.Errorf("store: get key %s: %w", key, err)
			}

			var update LocationUpdate
			if err := json.Unmarshal(data, &update); err != nil {
				continue // skip corrupt entries
			}

			if haversineKm(lat, lng, update.Lat, update.Lng) <= radiusKm {
				results = append(results, update)
			}
		}

		cursor = nextCursor
		if cursor == 0 {
			break
		}
	}
	return results, nil
}

// earthRadiusKm is the mean radius of the Earth.
const earthRadiusKm = 6371.0

// haversineKm computes the great-circle distance in kilometres between two
// geographic coordinates using the Haversine formula.
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
