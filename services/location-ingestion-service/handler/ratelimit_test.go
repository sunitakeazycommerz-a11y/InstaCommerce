package handler

import (
	"testing"

	"github.com/stretchr/testify/assert"
)

func TestConnectionRateLimiter_TokenBucket(t *testing.T) {
	// Rate: 10 per sec, burst: 3, max: 10
	limiter := NewConnectionRateLimiter(10.0, 3.0, 10)

	// First 3 requests (burst) must be allowed
	assert.True(t, limiter.Allow())
	assert.True(t, limiter.Allow())
	assert.True(t, limiter.Allow())

	// 4th immediate request should be throttled
	assert.False(t, limiter.Allow())

	// Release connections
	limiter.ReleaseConn()
	limiter.ReleaseConn()
	limiter.ReleaseConn()
	assert.Equal(t, int64(0), limiter.ActiveConnections())
}

func TestConnectionRateLimiter_MaxConnectionsLimit(t *testing.T) {
	// Rate: 100 per sec, burst: 10, max: 2
	limiter := NewConnectionRateLimiter(100.0, 10.0, 2)

	assert.True(t, limiter.Allow())
	assert.True(t, limiter.Allow())
	// 3rd exceeds maxConns limit of 2
	assert.False(t, limiter.Allow())

	// Releasing 1 connection frees up space
	limiter.ReleaseConn()
	assert.Equal(t, int64(1), limiter.ActiveConnections())
	assert.True(t, limiter.Allow())
}
