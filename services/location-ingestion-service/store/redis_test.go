package store

import (
	"testing"
	"time"

	"github.com/redis/go-redis/v9"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestNewLatestPositionStore_DefaultTTLIs60Seconds(t *testing.T) {
	client := redis.NewClient(&redis.Options{Addr: "localhost:6379"})
	defer client.Close()

	// When ttl <= 0, NewLatestPositionStore must set the 60-second heartbeat TTL (Fixes Issue #578)
	store, err := NewLatestPositionStore(client, 0)
	require.NoError(t, err)
	require.NotNil(t, store)

	assert.Equal(t, 60*time.Second, store.ttl)
	assert.Equal(t, DefaultRiderHeartbeatTTL, store.ttl)
}

func TestNewLatestPositionStore_CustomTTL(t *testing.T) {
	client := redis.NewClient(&redis.Options{Addr: "localhost:6379"})
	defer client.Close()

	store, err := NewLatestPositionStore(client, 30*time.Second)
	require.NoError(t, err)
	require.NotNil(t, store)

	assert.Equal(t, 30*time.Second, store.ttl)
}

func TestNewLatestPositionStore_NilClientRejected(t *testing.T) {
	store, err := NewLatestPositionStore(nil, 60*time.Second)
	assert.Error(t, err)
	assert.Nil(t, store)
	assert.Contains(t, err.Error(), "redis client must not be nil")
}

func TestKeyFor(t *testing.T) {
	key := keyFor("rider-xyz-123")
	assert.Equal(t, "rider:rider-xyz-123:location", key)
}
