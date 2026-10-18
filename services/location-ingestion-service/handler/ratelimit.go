package handler

import (
	"math"
	"sync"
	"sync/atomic"
	"time"
)

// ConnectionRateLimiter implements a token bucket rate limiter for WebSocket connection accept loops (Issue #618).
type ConnectionRateLimiter struct {
	mu           sync.Mutex
	rate         float64 // tokens per second
	capacity     float64 // maximum burst capacity
	tokens       float64
	lastRefill   time.Time
	activeConns  int64
	maxConns     int64
}

// NewConnectionRateLimiter creates a token bucket limiter.
// rate: allowed connections per second.
// burst: maximum burst capacity.
// maxConns: maximum concurrent active connections (e.g. 10000).
func NewConnectionRateLimiter(rate, burst float64, maxConns int64) *ConnectionRateLimiter {
	if rate <= 0 {
		rate = 100.0
	}
	if burst <= 0 {
		burst = 200.0
	}
	if maxConns <= 0 {
		maxConns = 20000
	}
	return &ConnectionRateLimiter{
		rate:       rate,
		capacity:   burst,
		tokens:     burst,
		lastRefill: time.Now(),
		maxConns:   maxConns,
	}
}

// Allow checks if a new connection is allowed under token bucket rate and max concurrent connection limits.
func (l *ConnectionRateLimiter) Allow() bool {
	l.mu.Lock()
	defer l.mu.Unlock()

	// Check max concurrent connections limit
	if atomic.LoadInt64(&l.activeConns) >= l.maxConns {
		return false
	}

	now := time.Now()
	elapsed := now.Sub(l.lastRefill).Seconds()
	l.lastRefill = now

	// Refill tokens
	l.tokens = math.Min(l.capacity, l.tokens+elapsed*l.rate)

	if l.tokens >= 1.0 {
		l.tokens -= 1.0
		atomic.AddInt64(&l.activeConns, 1)
		return true
	}

	return false
}

// ReleaseConn decrements the active connection count when a WebSocket connection terminates.
func (l *ConnectionRateLimiter) ReleaseConn() {
	if atomic.LoadInt64(&l.activeConns) > 0 {
		atomic.AddInt64(&l.activeConns, -1)
	}
}

// ActiveConnections returns the current active WebSocket connection count.
func (l *ConnectionRateLimiter) ActiveConnections() int64 {
	return atomic.LoadInt64(&l.activeConns)
}
