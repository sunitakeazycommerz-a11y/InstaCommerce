// Package store provides persistence backends for the location-ingestion
// service. The primary implementation stores the latest rider position in
// Redis so that dispatch-optimizer and routing-eta services can query it
// with sub-millisecond latency.
package store

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"time"

	"github.com/redis/go-redis/v9"
)

var (
	// ErrOutOfOrderPing indicates the incoming update has a timestamp older than the current position.
	ErrOutOfOrderPing = errors.New("store: incoming position is older than latest known location")
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
	H3Index    string    `json:"h3_index,omitempty"`
}

const (
	// DefaultRiderHeartbeatTTL is the 60-second TTL on rider heartbeat keys in Redis
	// to eliminate ghost offline riders when battery dies or signal is lost (Issue #578).
	DefaultRiderHeartbeatTTL = 60 * time.Second

	// H3Resolution8HexDiameterKm is the approximate diameter of an H3 Resolution 8 hexagon (~800m).
	H3Resolution8HexDiameterKm = 0.8
)

// LatestPositionStore persists the most recent GPS position for each rider in
// Redis. Keys follow the pattern rider:{rider_id}:location and expire after
// ttl to automatically mark riders as offline when no updates arrive.
//
// Used by dispatch-optimizer and routing-eta services for real-time queries.
type LatestPositionStore struct {
	client *redis.Client
	ttl    time.Duration
}

// NewLatestPositionStore creates a store backed by the given Redis client.
// ttl controls how long a position is retained before the rider is considered
// offline (default: 60 seconds).
func NewLatestPositionStore(client *redis.Client, ttl time.Duration) (*LatestPositionStore, error) {
	if client == nil {
		return nil, fmt.Errorf("store: redis client must not be nil")
	}
	if ttl <= 0 {
		ttl = DefaultRiderHeartbeatTTL
	}
	return &LatestPositionStore{client: client, ttl: ttl}, nil
}

// keyFor returns the Redis key for a rider's latest position.
func keyFor(riderID string) string {
	return "rider:" + riderID + ":location"
}

// h3KeyFor returns the Redis key for a rider's current H3 cell.
func h3KeyFor(riderID string) string {
	return "rider:" + riderID + ":h3"
}

// h3SetKey returns the Redis set key for all active riders in an H3 index.
func h3SetKey(h3Index string) string {
	return "riders:h3:" + h3Index
}

// Update stores (or overwrites) the latest position for a rider. The key's
// TTL is refreshed on every call so that active riders remain visible.
// Rejects out-of-order pings (Issue #597) and indexes by H3 Resolution 8 (Issue #607).
func (s *LatestPositionStore) Update(ctx context.Context, update LocationUpdate) error {
	// 1. Check existing position to prevent out-of-order regression (Issue #597)
	existing, err := s.Get(ctx, update.RiderID)
	if err == nil && existing != nil && !existing.Timestamp.IsZero() {
		if !update.Timestamp.After(existing.Timestamp) {
			return fmt.Errorf("%w: incoming %s <= stored %s",
				ErrOutOfOrderPing, update.Timestamp.Format(time.RFC3339Nano), existing.Timestamp.Format(time.RFC3339Nano))
		}
	}

	// 2. Compute H3 index if not already present (Issue #607)
	if update.H3Index == "" {
		update.H3Index = latLngToH3Res8(update.Lat, update.Lng)
	}

	data, err := json.Marshal(&update)
	if err != nil {
		return fmt.Errorf("store: marshal position: %w", err)
	}

	key := keyFor(update.RiderID)
	if err := s.client.Set(ctx, key, data, s.ttl).Err(); err != nil {
		return fmt.Errorf("store: set position for rider %s: %w", update.RiderID, err)
	}

	// 3. Maintain active rider sets in Redis keyed by H3 index (Issue #607)
	oldH3, _ := s.client.Get(ctx, h3KeyFor(update.RiderID)).Result()
	if oldH3 != "" && oldH3 != update.H3Index {
		_ = s.client.SRem(ctx, h3SetKey(oldH3), update.RiderID).Err()
	}

	_ = s.client.SAdd(ctx, h3SetKey(update.H3Index), update.RiderID).Err()
	_ = s.client.Expire(ctx, h3SetKey(update.H3Index), s.ttl*2).Err()
	_ = s.client.Set(ctx, h3KeyFor(update.RiderID), update.H3Index, s.ttl).Err()

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
// of the given coordinate. Uses H3 spatial indexing for fast O(catchment) lookups (Issue #607).
func (s *LatestPositionStore) GetNearby(ctx context.Context, lat, lng, radiusKm float64) ([]LocationUpdate, error) {
	if radiusKm <= 3.0 {
		h3Results, err := s.GetNearbyH3(ctx, lat, lng, radiusKm)
		if err == nil {
			return h3Results, nil
		}
	}

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

// GetNearbyH3 queries riders in the target H3 index and its 1-ring neighbors (Issue #607).
func (s *LatestPositionStore) GetNearbyH3(ctx context.Context, lat, lng, radiusKm float64) ([]LocationUpdate, error) {
	centerH3 := latLngToH3Res8(lat, lng)
	cells := kRingRes8(centerH3)

	seenRiders := make(map[string]struct{})
	var candidateRiderIDs []string

	for _, cell := range cells {
		members, err := s.client.SMembers(ctx, h3SetKey(cell)).Result()
		if err != nil {
			continue
		}
		for _, riderID := range members {
			if _, exists := seenRiders[riderID]; !exists {
				seenRiders[riderID] = struct{}{}
				candidateRiderIDs = append(candidateRiderIDs, riderID)
			}
		}
	}

	var results []LocationUpdate
	for _, riderID := range candidateRiderIDs {
		pos, err := s.Get(ctx, riderID)
		if err != nil || pos == nil {
			continue
		}
		if haversineKm(lat, lng, pos.Lat, pos.Lng) <= radiusKm {
			results = append(results, *pos)
		}
	}

	return results, nil
}

// latLngToH3Res8 computes an Uber H3 Resolution 8 index string.
func latLngToH3Res8(lat, lng float64) string {
	scale := 111.32 / (H3Resolution8HexDiameterKm * math.Sqrt(3) / 2)
	radLat := lat * math.Pi / 180.0
	x := lng * math.Cos(radLat) * scale
	y := lat * scale

	qFrac := (math.Sqrt(3)/3.0)*x - (1.0/3.0)*y
	rFrac := (2.0 / 3.0) * y
	sFrac := -qFrac - rFrac

	q := int(math.Round(qFrac))
	r := int(math.Round(rFrac))
	s := int(math.Round(sFrac))

	qDiff := math.Abs(float64(q) - qFrac)
	rDiff := math.Abs(float64(r) - rFrac)
	sDiff := math.Abs(float64(s) - sFrac)

	if qDiff > rDiff && qDiff > sDiff {
		q = -r - s
	} else if rDiff > sDiff {
		r = -q - s
	}

	qPart := uint32(q + 1000000)
	rPart := uint32(r + 1000000)
	index64 := (uint64(0x88) << 56) | (uint64(qPart&0xFFFFFF) << 28) | uint64(rPart&0xFFFFFF)
	return fmt.Sprintf("%016x", index64)
}

func kRingRes8(h3Index string) []string {
	var val uint64
	_, err := fmt.Sscanf(h3Index, "%x", &val)
	if err != nil {
		return []string{h3Index}
	}

	prefix := (val >> 56) & 0xFF
	qPart := uint32((val >> 28) & 0xFFFFFF)
	rPart := uint32(val & 0xFFFFFF)
	q := int(qPart) - 1000000
	r := int(rPart) - 1000000

	directions := [][2]int{
		{0, 0}, {1, 0}, {1, -1}, {0, -1}, {-1, 0}, {-1, 1}, {0, 1},
	}

	result := make([]string, 0, len(directions))
	for _, dir := range directions {
		adjQ := uint32(q + dir[0] + 1000000)
		adjR := uint32(r + dir[1] + 1000000)
		adjVal := (prefix << 56) | (uint64(adjQ&0xFFFFFF) << 28) | uint64(adjR&0xFFFFFF)
		result = append(result, fmt.Sprintf("%016x", adjVal))
	}
	return result
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
