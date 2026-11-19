package handler

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"sync/atomic"
	"time"
)

// ErrRiderAlreadyLocked indicates a candidate rider is currently locked by another dispatch.
var ErrRiderAlreadyLocked = errors.New("rider is already locked by concurrent dispatch")

// RiderMutex defines locking contracts for candidate rider assignments.
type RiderMutex interface {
	Lock(ctx context.Context, riderID string, ttl time.Duration) (func() error, error)
}

type lockEntry struct {
	expiresAt time.Time
	token     uint64
}

type inMemoryRiderMutex struct {
	mu      sync.Mutex
	locks   map[string]lockEntry
	nextSeq atomic.Uint64
}

// NewInMemoryRiderMutex creates a thread-safe mutex tracker for rider assignments.
func NewInMemoryRiderMutex() RiderMutex {
	return &inMemoryRiderMutex{
		locks: make(map[string]lockEntry),
	}
}

func (m *inMemoryRiderMutex) Lock(_ context.Context, riderID string, ttl time.Duration) (func() error, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	now := time.Now()
	for id, entry := range m.locks {
		if !now.Before(entry.expiresAt) {
			delete(m.locks, id)
		}
	}
	if existing, exists := m.locks[riderID]; exists && now.Before(existing.expiresAt) {
		return nil, fmt.Errorf("rider %s: %w", riderID, ErrRiderAlreadyLocked)
	}

	token := m.nextSeq.Add(1)
	m.locks[riderID] = lockEntry{expiresAt: now.Add(ttl), token: token}
	var unlockOnce sync.Once
	return func() error {
		unlockOnce.Do(func() {
			m.mu.Lock()
			defer m.mu.Unlock()
			if cur, ok := m.locks[riderID]; ok && cur.token == token {
				delete(m.locks, riderID)
			}
		})
		return nil
	}, nil
}
