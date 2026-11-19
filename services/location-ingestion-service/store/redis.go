// Package store provides persistence backends for the location-ingestion service.
package store

import (
	"context"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"time"

	"github.com/redis/go-redis/v9"
)

// ErrOutOfOrderPing indicates the incoming update has a timestamp <= the current position.
var ErrOutOfOrderPing = errors.New("store: incoming position is older than latest known location")

// LocationUpdate mirrors handler.LocationUpdate for the persistence package.
type LocationUpdate struct {
	RiderID       string    `json:"rider_id"`
	Lat           float64   `json:"lat"`
	Lng           float64   `json:"lng"`
	Accuracy      float64   `json:"accuracy_meters"`
	Speed         float64   `json:"speed_kmh"`
	Heading       float64   `json:"heading_degrees"`
	Timestamp     time.Time `json:"timestamp"`
	BatteryPct    float64   `json:"battery_pct"`
	H3Index       string    `json:"h3_index,omitempty"`
	HasSpeed      bool      `json:"-"`
	HasHeading    bool      `json:"-"`
	HasAccuracy   bool      `json:"-"`
	HasBatteryPct bool      `json:"-"`
}

const (
	DefaultRiderHeartbeatTTL   = 60 * time.Second
	H3Resolution8HexDiameterKm = 0.8
)

var updatePositionScript = redis.NewScript(`
local existing = tonumber(redis.call('HGET', KEYS[1], 'timestamp_ms'))
if existing ~= nil and existing > 0 and tonumber(ARGV[1]) <= existing then
	return {0, tostring(existing)}
end
local old_h3 = redis.call('HGET', KEYS[1], 'h3_index')
local ttl_ms = tonumber(ARGV[2])
for i = 5, #ARGV, 2 do
	redis.call('HSET', KEYS[1], ARGV[i], ARGV[i+1])
end
if ttl_ms ~= nil and ttl_ms > 0 then
	redis.call('PEXPIRE', KEYS[1], ttl_ms)
end
local new_h3 = ARGV[3]
local rider_id = ARGV[4]
if new_h3 ~= '' then
	if old_h3 and old_h3 ~= '' and old_h3 ~= new_h3 then
		redis.call('SREM', 'riders:h3:' .. old_h3, rider_id)
	end
	local h3_key = 'riders:h3:' .. new_h3
	redis.call('SADD', h3_key, rider_id)
	if ttl_ms ~= nil and ttl_ms > 0 then
		redis.call('PEXPIRE', h3_key, ttl_ms * 2)
	end
end
return {1, ''}
`)

// LatestPositionStore persists the most recent GPS position for each rider in Redis.
type LatestPositionStore struct {
	client    *redis.Client
	ttl       time.Duration
	keyPrefix string
}

// NewLatestPositionStore creates a store backed by client with default key layout.
func NewLatestPositionStore(client *redis.Client, ttl time.Duration) (*LatestPositionStore, error) {
	return NewLatestPositionStoreWithPrefix(client, "", ttl)
}

// NewLatestPositionStoreWithPrefix creates a store with an explicit Redis hash key prefix and TTL.
func NewLatestPositionStoreWithPrefix(client *redis.Client, keyPrefix string, ttl time.Duration) (*LatestPositionStore, error) {
	if client == nil {
		return nil, fmt.Errorf("store: redis client must not be nil")
	}
	if ttl <= 0 {
		ttl = DefaultRiderHeartbeatTTL
	}
	return &LatestPositionStore{client: client, ttl: ttl, keyPrefix: keyPrefix}, nil
}

func keyFor(riderID string) string {
	return "rider:" + riderID + ":location"
}

func (s *LatestPositionStore) positionKey(riderID string) string {
	if s.keyPrefix != "" {
		return s.keyPrefix + riderID
	}
	return keyFor(riderID)
}

func (s *LatestPositionStore) scanPattern() string {
	if s.keyPrefix != "" {
		return s.keyPrefix + "*"
	}
	return "rider:*:location"
}

func (s *LatestPositionStore) extractRiderID(key string) string {
	if s.keyPrefix != "" {
		return strings.TrimPrefix(key, s.keyPrefix)
	}
	trimmed := strings.TrimPrefix(key, "rider:")
	trimmed = strings.TrimSuffix(trimmed, ":location")
	return trimmed
}

func h3SetKey(h3Index string) string {
	return "riders:h3:" + h3Index
}

// Update atomically stores the latest position for a rider via Lua CAS.
func (s *LatestPositionStore) Update(ctx context.Context, update LocationUpdate) error {
	if update.Timestamp.IsZero() {
		update.Timestamp = time.Now().UTC()
	}
	if update.H3Index == "" {
		update.H3Index = latLngToH3Res8(update.Lat, update.Lng)
	}

	incomingMs := update.Timestamp.UnixMilli()
	ttlMs := s.ttl.Milliseconds()

	args := []any{
		incomingMs,
		ttlMs,
		update.H3Index,
		update.RiderID,
		"lat", strconv.FormatFloat(update.Lat, 'f', -1, 64),
		"lng", strconv.FormatFloat(update.Lng, 'f', -1, 64),
		"timestamp_ms", strconv.FormatInt(incomingMs, 10),
	}
	if update.HasSpeed || update.Speed != 0 {
		args = append(args, "speed", strconv.FormatFloat(update.Speed, 'f', -1, 64))
	}
	if update.HasHeading || update.Heading != 0 {
		args = append(args, "heading", strconv.FormatFloat(update.Heading, 'f', -1, 64))
	}
	if update.HasAccuracy || update.Accuracy != 0 {
		args = append(args, "accuracy", strconv.FormatFloat(update.Accuracy, 'f', -1, 64))
	}
	if update.HasBatteryPct || update.BatteryPct != 0 {
		args = append(args, "battery_pct", strconv.FormatFloat(update.BatteryPct, 'f', -1, 64))
	}
	if update.H3Index != "" {
		args = append(args, "h3_index", update.H3Index)
	}

	rawRes, err := updatePositionScript.Run(ctx, s.client, []string{s.positionKey(update.RiderID)}, args...).Result()
	if err != nil {
		return fmt.Errorf("store: update position for rider %s: %w", update.RiderID, err)
	}

	vals, ok := rawRes.([]any)
	if ok && len(vals) >= 1 {
		accepted, _ := toInt64(vals[0])
		if accepted == 0 {
			existing := ""
			if len(vals) >= 2 {
				existing = fmt.Sprint(vals[1])
			}
			return fmt.Errorf("%w: %d <= %s", ErrOutOfOrderPing, incomingMs, existing)
		}
	}
	return nil
}

func toInt64(v any) (int64, bool) {
	switch n := v.(type) {
	case int64:
		return n, true
	case int:
		return int64(n), true
	case string:
		parsed, err := strconv.ParseInt(n, 10, 64)
		return parsed, err == nil
	default:
		return 0, false
	}
}

// Get retrieves the latest known position for riderID, returning nil if absent.
func (s *LatestPositionStore) Get(ctx context.Context, riderID string) (*LocationUpdate, error) {
	return s.getByKey(ctx, s.positionKey(riderID), riderID)
}

func (s *LatestPositionStore) getByKey(ctx context.Context, key, riderID string) (*LocationUpdate, error) {
	fields, err := s.client.HGetAll(ctx, key).Result()
	if err != nil {
		return nil, fmt.Errorf("store: get position for key %s: %w", key, err)
	}
	if len(fields) == 0 {
		return nil, nil
	}

	lat, err := strconv.ParseFloat(fields["lat"], 64)
	if err != nil {
		return nil, fmt.Errorf("store: invalid lat for %s: %w", key, err)
	}
	lng, err := strconv.ParseFloat(fields["lng"], 64)
	if err != nil {
		return nil, fmt.Errorf("store: invalid lng for %s: %w", key, err)
	}
	tsMs, err := strconv.ParseInt(fields["timestamp_ms"], 10, 64)
	if err != nil {
		return nil, fmt.Errorf("store: invalid timestamp_ms for %s: %w", key, err)
	}

	update := &LocationUpdate{
		RiderID:   riderID,
		Lat:       lat,
		Lng:       lng,
		Timestamp: time.UnixMilli(tsMs).UTC(),
		H3Index:   fields["h3_index"],
	}
	if v, ok := fields["speed"]; ok {
		if parsed, err := strconv.ParseFloat(v, 64); err == nil {
			update.Speed = parsed
			update.HasSpeed = true
		}
	}
	if v, ok := fields["heading"]; ok {
		if parsed, err := strconv.ParseFloat(v, 64); err == nil {
			update.Heading = parsed
			update.HasHeading = true
		}
	}
	if v, ok := fields["accuracy"]; ok {
		if parsed, err := strconv.ParseFloat(v, 64); err == nil {
			update.Accuracy = parsed
			update.HasAccuracy = true
		}
	}
	if v, ok := fields["battery_pct"]; ok {
		if parsed, err := strconv.ParseFloat(v, 64); err == nil {
			update.BatteryPct = parsed
			update.HasBatteryPct = true
		}
	}
	return update, nil
}

// GetNearby returns all riders within radiusKm of (lat, lng).
func (s *LatestPositionStore) GetNearby(ctx context.Context, lat, lng, radiusKm float64) ([]LocationUpdate, error) {
	if radiusKm <= 3.0 {
		h3Results, err := s.GetNearbyH3(ctx, lat, lng, radiusKm)
		if err == nil {
			return h3Results, nil
		}
	}

	var cursor uint64
	var results []LocationUpdate
	pattern := s.scanPattern()

	for {
		keys, nextCursor, err := s.client.Scan(ctx, cursor, pattern, 100).Result()
		if err != nil {
			return nil, fmt.Errorf("store: scan rider keys: %w", err)
		}

		for _, key := range keys {
			riderID := s.extractRiderID(key)
			update, err := s.getByKey(ctx, key, riderID)
			if err != nil || update == nil {
				continue
			}
			if haversineKm(lat, lng, update.Lat, update.Lng) <= radiusKm {
				results = append(results, *update)
			}
		}

		cursor = nextCursor
		if cursor == 0 {
			break
		}
	}
	return results, nil
}

// GetNearbyH3 queries riders in the target H3 index and its 1-ring neighbors.
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
