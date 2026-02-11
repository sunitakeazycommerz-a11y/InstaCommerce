package handler

import (
	"sync"
	"time"
)

// IdempotencyStore prevents duplicate processing of webhook events.
//
// It uses an in-memory LRU-style map with a TTL for each entry. In a
// production deployment this would be backed by Redis for cross-instance
// deduplication; this implementation is suitable for single-instance or
// integration-test scenarios.
type IdempotencyStore struct {
	mu      sync.RWMutex
	seen    map[string]time.Time
	ttl     time.Duration
	maxSize int
	done    chan struct{}
}

// NewIdempotencyStore creates an IdempotencyStore that keeps event IDs for ttl
// and evicts the oldest entries when maxSize is exceeded.
//
// cleanupInterval controls how often expired entries are reaped. A goroutine is
// started for this purpose and can be stopped by calling Close.
func NewIdempotencyStore(ttl time.Duration, maxSize int, cleanupInterval time.Duration) *IdempotencyStore {
	s := &IdempotencyStore{
		seen:    make(map[string]time.Time, maxSize),
		ttl:     ttl,
		maxSize: maxSize,
		done:    make(chan struct{}),
	}
	go s.cleanupLoop(cleanupInterval)
	return s
}

// IsDuplicate returns true if eventID has been seen within the TTL window.
func (s *IdempotencyStore) IsDuplicate(eventID string) bool {
	s.mu.RLock()
	defer s.mu.RUnlock()

	expiry, ok := s.seen[eventID]
	if !ok {
		return false
	}
	return time.Now().Before(expiry)
}

// Mark records eventID so that subsequent calls to IsDuplicate return true
// until the TTL expires. If the store is at capacity the oldest entry is
// evicted first.
func (s *IdempotencyStore) Mark(eventID string) {
	s.mu.Lock()
	defer s.mu.Unlock()

	// Evict oldest entry when at capacity.
	if len(s.seen) >= s.maxSize {
		s.evictOldestLocked()
	}
	s.seen[eventID] = time.Now().Add(s.ttl)
}

// Close stops the background cleanup goroutine. It is safe to call multiple
// times.
func (s *IdempotencyStore) Close() {
	select {
	case <-s.done:
		// Already closed.
	default:
		close(s.done)
	}
}

// cleanupLoop periodically removes expired entries.
func (s *IdempotencyStore) cleanupLoop(interval time.Duration) {
	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	for {
		select {
		case <-ticker.C:
			s.removeExpired()
		case <-s.done:
			return
		}
	}
}

// removeExpired deletes all entries whose TTL has elapsed.
func (s *IdempotencyStore) removeExpired() {
	now := time.Now()
	s.mu.Lock()
	defer s.mu.Unlock()

	for id, expiry := range s.seen {
		if now.After(expiry) {
			delete(s.seen, id)
		}
	}
}

// evictOldestLocked removes the entry with the earliest expiry. Caller must
// hold s.mu.
func (s *IdempotencyStore) evictOldestLocked() {
	var oldestID string
	var oldestTime time.Time
	first := true

	for id, expiry := range s.seen {
		if first || expiry.Before(oldestTime) {
			oldestID = id
			oldestTime = expiry
			first = false
		}
	}
	if !first {
		delete(s.seen, oldestID)
	}
}
