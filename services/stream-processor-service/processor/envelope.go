package processor

import (
	"encoding/json"
	"strconv"
	"strings"
	"sync"
	"time"
)

const maxBoundedLabelValues = 1000

// labelCardinalityGuard bounds the number of unique dynamic label values per metric dimension.
type labelCardinalityGuard struct {
	mu   sync.Mutex
	seen map[string]struct{}
	max  int
}

func newLabelCardinalityGuard(max int) *labelCardinalityGuard {
	if max <= 0 {
		max = maxBoundedLabelValues
	}
	return &labelCardinalityGuard{
		seen: make(map[string]struct{}),
		max:  max,
	}
}

func (g *labelCardinalityGuard) normalize(val, fallback string) string {
	val = strings.TrimSpace(val)
	if val == "" {
		if fallback != "" {
			return fallback
		}
		return "unknown"
	}
	if len(val) > 64 {
		val = val[:64]
	}
	g.mu.Lock()
	defer g.mu.Unlock()
	if _, ok := g.seen[val]; ok {
		return val
	}
	if len(g.seen) >= g.max {
		return "other"
	}
	g.seen[val] = struct{}{}
	return val
}

func normalizeEventType(raw string, aliases map[string]string) string {
	trimmed := strings.TrimSpace(raw)
	if canonical, ok := aliases[strings.ToUpper(trimmed)]; ok {
		return canonical
	}
	return trimmed
}

// unwrapEnvelope extracts both outer envelope metadata and inner payload map when an EventEnvelope is used,
// or returns the flat object as both meta and body when no nested payload object is present.
func unwrapEnvelope(data []byte) (map[string]any, map[string]any, error) {
	var raw map[string]any
	if err := json.Unmarshal(data, &raw); err != nil {
		return nil, nil, err
	}
	if raw == nil {
		return map[string]any{}, map[string]any{}, nil
	}
	if p, ok := raw["payload"]; ok && p != nil {
		switch typed := p.(type) {
		case map[string]any:
			return raw, typed, nil
		case string:
			var nested map[string]any
			if err := json.Unmarshal([]byte(typed), &nested); err == nil && nested != nil {
				return raw, nested, nil
			}
		}
	}
	return raw, raw, nil
}

func firstString(maps []map[string]any, keys ...string) string {
	for _, m := range maps {
		if m == nil {
			continue
		}
		for _, k := range keys {
			v, ok := m[k]
			if !ok || v == nil {
				continue
			}
			switch s := v.(type) {
			case string:
				if strings.TrimSpace(s) != "" {
					return strings.TrimSpace(s)
				}
			}
		}
	}
	return ""
}

func firstInt64(maps []map[string]any, keys ...string) (int64, bool) {
	for _, m := range maps {
		if m == nil {
			continue
		}
		for _, k := range keys {
			v, ok := m[k]
			if !ok || v == nil {
				continue
			}
			switch n := v.(type) {
			case float64:
				return int64(n), true
			case int64:
				return n, true
			case int:
				return int64(n), true
			case json.Number:
				if i, err := n.Int64(); err == nil {
					return i, true
				}
				if f, err := n.Float64(); err == nil {
					return int64(f), true
				}
			case string:
				if i, err := strconv.ParseInt(strings.TrimSpace(n), 10, 64); err == nil {
					return i, true
				}
				if f, err := strconv.ParseFloat(strings.TrimSpace(n), 64); err == nil {
					return int64(f), true
				}
			}
		}
	}
	return 0, false
}

func firstFloat64(maps []map[string]any, keys ...string) (float64, bool) {
	for _, m := range maps {
		if m == nil {
			continue
		}
		for _, k := range keys {
			v, ok := m[k]
			if !ok || v == nil {
				continue
			}
			switch n := v.(type) {
			case float64:
				return n, true
			case int64:
				return float64(n), true
			case int:
				return float64(n), true
			case json.Number:
				if f, err := n.Float64(); err == nil {
					return f, true
				}
			case string:
				if f, err := strconv.ParseFloat(strings.TrimSpace(n), 64); err == nil {
					return f, true
				}
			}
		}
	}
	return 0, false
}

func firstTime(maps []map[string]any, keys ...string) (time.Time, bool) {
	for _, m := range maps {
		if m == nil {
			continue
		}
		for _, k := range keys {
			v, ok := m[k]
			if !ok || v == nil {
				continue
			}
			if t, ok := parseFlexibleTime(v); ok {
				return t, true
			}
		}
	}
	return time.Time{}, false
}

func parseFlexibleTime(v any) (time.Time, bool) {
	switch val := v.(type) {
	case string:
		s := strings.TrimSpace(val)
		if s == "" {
			return time.Time{}, false
		}
		for _, layout := range []string{
			time.RFC3339Nano,
			time.RFC3339,
			"2006-01-02T15:04:05.999999999",
			"2006-01-02T15:04:05",
		} {
			if t, err := time.Parse(layout, s); err == nil {
				return t, true
			}
		}
		if n, err := strconv.ParseInt(s, 10, 64); err == nil {
			return unixNumberToTime(n), true
		}
	case float64:
		if val > 0 {
			return unixNumberToTime(int64(val)), true
		}
	case int64:
		if val > 0 {
			return unixNumberToTime(val), true
		}
	case int:
		if val > 0 {
			return unixNumberToTime(int64(val)), true
		}
	}
	return time.Time{}, false
}

func unixNumberToTime(n int64) time.Time {
	if n > 1e12 {
		return time.UnixMilli(n).UTC()
	}
	return time.Unix(n, 0).UTC()
}

func evictExpired[T any](items []T, timestamp func(T) time.Time, cutoff time.Time) ([]T, bool) {
	idx := 0
	for idx < len(items) && timestamp(items[idx]).Before(cutoff) {
		idx++
	}
	if idx == 0 {
		return items, false
	}
	remaining := len(items) - idx
	copy(items, items[idx:])
	clear(items[remaining:])
	return items[:remaining], true
}
