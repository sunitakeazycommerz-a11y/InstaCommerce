// Package store provides persistence backends for the location-ingestion
// service. The primary implementation stores the latest rider position in
// Redis so that dispatch-optimizer and routing-eta services can query it
// with sub-millisecond latency.
package store

import (
	"context"
	"errors"
	"fmt"
	"math"
	"strconv"
	"time"

	"github.com/redis/go-redis/v9"
)

var (
	// ErrOutOfOrderPing indicates the incoming update has a timestamp older than or equal to the current position.
	ErrOutOfOrderPing = errors.New("store: incoming position is older than latest known location")
)

// LocationUpdate mirrors handler.LocationUpdate and is duplicated here to
// avoid a circular import. In a larger codebase this would live in a shared
// domain package.
type LocationUpdate struct {
	RiderID     string    `json:"rider_id"`
	Lat         float64   `json:"lat"`
	Lng         float64   `json:"lng"`
	Accuracy    float64   `json:"accuracy_meters"`
	Speed       float64   `json:"speed_kmh"`
	Heading     float64   `json:"heading_degrees"`
	Timestamp   time.Time `json:"timestamp"`
	BatteryPct  float64   `json:"battery_pct"`
	H3Index     string    `json:"h3_index,omitempty"`
	HasSpeed    bool      `json:"-"`
	HasHeading  bool      `json:"-"`
	HasAccuracy bool      `json:"-"`
}

const (
	// DefaultRiderHeartbeatTTL is the 60-second TTL on rider heartbeat keys in Redis
	// to eliminate ghost offline riders when battery dies or signal is lost (Issue #578).
	DefaultRiderHeartbeatTTL = 60 * time.Second

	// H3Resolution8HexDiameterKm is the approximate diameter of an H3 Resolution 8 hexagon (~800m).
	H3Resolution8HexDiameterKm = 0.8
)

// updatePositionScript atomically compares timestamp_ms against the stored latest position
// and writes the updated hash fields + H3 spatial set membership without TOCTOU races.
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

// LatestPositionStore persists the most recent GPS position for each rider in
// Redis. Default keys follow rider:{rider_id}:location unless a custom prefix
// (such as "rider:location:") is configured.
type LatestPositionStore struct {
	client    *redis.Client
	ttl       time.Duration
	keyPrefix string
}

// NewLatestPositionStore creates a store backed by the given Redis client.
// ttl controls how long a position is retained before the rider is considered
// offline (default: 60 seconds).
func NewLatestPositionStore(client *redis.Client, ttl time.Duration) (*LatestPositionStore, error) {
	return NewLatestPositionStoreWithPrefix(client, "", ttl)
}

// NewLatestPositionStoreWithPrefix creates a store with an explicit Redis hash key prefix
// (for example "rider:location:") and TTL.
func NewLatestPositionStoreWithPrefix(client *redis.Client, keyPrefix string, ttl time.Duration) (*LatestPositionStore, error) {
	if client == nil {
		return nil, fmt.Errorf("store: redis client must not be nil")
	}
	if ttl <= 0 {
		ttl = DefaultRiderHeartbeatTTL
	}
	return &LatestPositionStore{client: client, ttl: ttl, keyPrefix: keyPrefix}, nil
}

// keyFor returns the default Redis key for a rider's latest position.
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

// h3SetKey returns the Redis set key for all active riders in an H3 index.
func h3SetKey(h3Index string) string {
	return "riders:h3:" + h3Index
}

// Update atomically stores (or updates) the latest position for a rider via Lua CAS.
// Rejects out-of-order pings (Issue #597) and maintains H3 Resolution 8 rider sets (Issue #607).
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
	if update.BatteryPct != 0 {
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

// Get retrieves the latest known position for riderID. Returns nil if the
// rider is not found (key expired or never set).
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
		}
	}
	return update, nil
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
	pattern := s.scanPattern()

	for {
		keys, nextCursor, err := s.client.Scan(ctx, cursor, pattern, 100).Result()
		if err != nil {
			return nil, fmt.Errorf("store: scan rider keys: %w", err)
		}

		for _, key := range keys {
			update, err := s.getByKey(ctx, key, key)
			if err != nil {
				continue // skip corrupt entries
			}
			if update == nil {
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
	if a < 0 {
		a = 0
	} else if a > 1 {
		a = 1
	}

	c := 2 * math.Atan2(math.Sqrt(a), math.Sqrt(1-a))
	return earthRadiusKm * c
}

// degToRad converts degrees to radians.
func degToRad(deg float64) float64 {
	return deg * math.Pi / 180
}
