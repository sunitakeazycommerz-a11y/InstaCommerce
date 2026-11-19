package store

import (
	"context"
	"sync"
	"testing"
	"time"

	"github.com/redis/go-redis/v9"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestNewLatestPositionStore_DefaultTTLIs60Seconds(t *testing.T) {
	client := redis.NewClient(&redis.Options{Addr: "localhost:6379"})
	defer client.Close()

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
	assert.Equal(t, 30*time.Second, store.ttl)
}

func TestNewLatestPositionStore_NilClientRejected(t *testing.T) {
	store, err := NewLatestPositionStore(nil, 60*time.Second)
	assert.Error(t, err)
	assert.Nil(t, store)
	assert.Contains(t, err.Error(), "redis client must not be nil")
}

func TestKeyFor(t *testing.T) {
	assert.Equal(t, "rider:rider-xyz-123:location", keyFor("rider-xyz-123"))
}

func TestLatestPositionStore_UpdateAtomicLuaAndPrefix(t *testing.T) {
	srv := startFakeRedisServer(t)
	client := newTestRedisClient(t, srv.addr)

	posStore, err := NewLatestPositionStoreWithPrefix(client, "rider:location:", 10*time.Minute)
	require.NoError(t, err)

	ts := time.UnixMilli(1700000005000).UTC()
	err = posStore.Update(context.Background(), LocationUpdate{
		RiderID:     "rider-1",
		Lat:         12.9716,
		Lng:         77.5946,
		Heading:     90.0,
		Speed:       8.5,
		Accuracy:    4.2,
		Timestamp:   ts,
		HasHeading:  true,
		HasSpeed:    true,
		HasAccuracy: true,
	})
	require.NoError(t, err)

	cmds := srv.commandList()
	assert.Contains(t, cmds, "EVAL")
	assert.NotContains(t, cmds, "HGET")
	assert.NotContains(t, cmds, "HSET")
	assert.NotContains(t, cmds, "HMGET")

	got, err := posStore.Get(context.Background(), "rider-1")
	require.NoError(t, err)
	require.NotNil(t, got)
	assert.Equal(t, "rider-1", got.RiderID)
	assert.InDelta(t, 12.9716, got.Lat, 1e-6)
	assert.InDelta(t, 77.5946, got.Lng, 1e-6)
	assert.Equal(t, ts.UnixMilli(), got.Timestamp.UnixMilli())

	srv.mu.Lock()
	_, hashExists := srv.hashes["rider:location:rider-1"]
	h3Idx := srv.hashes["rider:location:rider-1"]["h3_index"]
	srv.mu.Unlock()
	assert.True(t, hashExists)
	assert.NotEmpty(t, h3Idx)

	nearby, err := client.SMembers(context.Background(), "riders:h3:"+h3Idx).Result()
	require.NoError(t, err)
	assert.Equal(t, []string{"rider-1"}, nearby)
}

func TestLatestPositionStore_RejectsOutOfOrderAndEqualTimestampAtomically(t *testing.T) {
	srv := startFakeRedisServer(t)
	client := newTestRedisClient(t, srv.addr)

	posStore, err := NewLatestPositionStoreWithPrefix(client, "rider:location:", 10*time.Minute)
	require.NoError(t, err)

	tNew := time.UnixMilli(1700000010000).UTC()
	require.NoError(t, posStore.Update(context.Background(), LocationUpdate{
		RiderID:   "rider-1",
		Lat:       12.9716,
		Lng:       77.5946,
		Timestamp: tNew,
	}))

	err = posStore.Update(context.Background(), LocationUpdate{
		RiderID:   "rider-1",
		Lat:       12.9352,
		Lng:       77.6245,
		Timestamp: tNew.Add(-5 * time.Second),
	})
	require.ErrorIs(t, err, ErrOutOfOrderPing)

	err = posStore.Update(context.Background(), LocationUpdate{
		RiderID:   "rider-1",
		Lat:       12.9352,
		Lng:       77.6245,
		Timestamp: tNew,
	})
	require.ErrorIs(t, err, ErrOutOfOrderPing)

	got, err := posStore.Get(context.Background(), "rider-1")
	require.NoError(t, err)
	require.NotNil(t, got)
	assert.InDelta(t, 12.9716, got.Lat, 1e-6)
	assert.InDelta(t, 77.5946, got.Lng, 1e-6)
	assert.Equal(t, tNew.UnixMilli(), got.Timestamp.UnixMilli())
}

func TestLatestPositionStore_UpdateMovesH3Index(t *testing.T) {
	srv := startFakeRedisServer(t)
	client := newTestRedisClient(t, srv.addr)

	posStore, err := NewLatestPositionStoreWithPrefix(client, "rider:location:", 10*time.Minute)
	require.NoError(t, err)

	t1 := time.UnixMilli(1700000001000).UTC()
	require.NoError(t, posStore.Update(context.Background(), LocationUpdate{
		RiderID:   "rider-1",
		Lat:       12.9716,
		Lng:       77.5946,
		Timestamp: t1,
	}))

	srv.mu.Lock()
	cellA := srv.hashes["rider:location:rider-1"]["h3_index"]
	srv.mu.Unlock()
	require.NotEmpty(t, cellA)

	t2 := t1.Add(10 * time.Second)
	require.NoError(t, posStore.Update(context.Background(), LocationUpdate{
		RiderID:   "rider-1",
		Lat:       13.0358,
		Lng:       77.5970,
		Timestamp: t2,
	}))

	srv.mu.Lock()
	cellB := srv.hashes["rider:location:rider-1"]["h3_index"]
	srv.mu.Unlock()
	require.NotEmpty(t, cellB)
	require.NotEqual(t, cellA, cellB)

	membersA, err := client.SMembers(context.Background(), "riders:h3:"+cellA).Result()
	require.NoError(t, err)
	assert.Empty(t, membersA)

	membersB, err := client.SMembers(context.Background(), "riders:h3:"+cellB).Result()
	require.NoError(t, err)
	assert.Equal(t, []string{"rider-1"}, membersB)
}

func TestLatestPositionStore_ConcurrentUpdatesNeverRegressTimestamp(t *testing.T) {
	srv := startFakeRedisServer(t)
	client := newTestRedisClient(t, srv.addr)

	posStore, err := NewLatestPositionStoreWithPrefix(client, "rider:location:", 10*time.Minute)
	require.NoError(t, err)

	base := time.UnixMilli(1700000000000).UTC()
	const n = 40

	var wg sync.WaitGroup
	for i := n; i >= 1; i-- {
		wg.Add(1)
		go func(offset int) {
			defer wg.Done()
			err := posStore.Update(context.Background(), LocationUpdate{
				RiderID:   "rider-race",
				Lat:       12.97 + float64(offset)*0.0001,
				Lng:       77.59,
				Timestamp: base.Add(time.Duration(offset) * time.Millisecond),
			})
			if err != nil {
				assert.ErrorIs(t, err, ErrOutOfOrderPing)
			}
		}(i)
	}
	wg.Wait()

	got, err := posStore.Get(context.Background(), "rider-race")
	require.NoError(t, err)
	require.NotNil(t, got)
	assert.Equal(t, base.Add(n*time.Millisecond).UnixMilli(), got.Timestamp.UnixMilli())
}

func TestLatestPositionStore_GetNearbyScanExtractsRiderID(t *testing.T) {
	for _, prefix := range []string{"", "rider:location:"} {
		srv := startFakeRedisServer(t)
		client := newTestRedisClient(t, srv.addr)
		posStore, err := NewLatestPositionStoreWithPrefix(client, prefix, 10*time.Minute)
		require.NoError(t, err)

		require.NoError(t, posStore.Update(context.Background(), LocationUpdate{
			RiderID:   "rider-scan-42",
			Lat:       12.9716,
			Lng:       77.5946,
			Timestamp: time.Now().UTC(),
		}))

		// radiusKm > 3.0 triggers SCAN fallback instead of H3 1-ring query.
		nearby, err := posStore.GetNearby(context.Background(), 12.9720, 77.5950, 5.0)
		require.NoError(t, err)
		require.Len(t, nearby, 1)
		assert.Equal(t, "rider-scan-42", nearby[0].RiderID)
	}
}
