package processor

import (
	"context"
	"encoding/json"
	"errors"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/redis/go-redis/v9"
	"github.com/segmentio/kafka-go"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestStreamDispatcherConcurrentOutOfOrderCompletionIsMonotonic(t *testing.T) {
	cfg := StreamConfig{
		BufferSize:     64,
		HighWatermark:  48,
		LowWatermark:   16,
		WorkerCount:    8,
		PauseCheckWait: 2 * time.Millisecond,
	}

	handler := func(_ context.Context, msg kafka.Message) error {
		if msg.Offset%3 == 0 {
			time.Sleep(3 * time.Millisecond)
		}
		return nil
	}

	rec := &commitRecorder{}
	dispatcher := NewStreamDispatcher(cfg, nil, handler, rec.commit)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	dispatcher.Start(ctx)

	const total = 100
	for p := 0; p < 3; p++ {
		for off := int64(0); off < total; off++ {
			require.NoError(t, dispatcher.Submit(ctx, kafka.Message{
				Topic:     "orders.events",
				Partition: p,
				Offset:    off,
			}))
		}
	}

	dispatcher.Stop()

	for p := 0; p < 3; p++ {
		offsets := rec.offsetsFor("orders.events", p)
		require.NotEmpty(t, offsets)
		assert.Equal(t, int64(total-1), offsets[len(offsets)-1])
		for i := 1; i < len(offsets); i++ {
			assert.Greater(t, offsets[i], offsets[i-1])
		}
	}
}

func TestStreamDispatcherDLQHandledFailureDoesNotWedgePartition(t *testing.T) {
	var dlqOffsets []int64
	cfg := StreamConfig{
		BufferSize:     16,
		HighWatermark:  12,
		LowWatermark:   4,
		WorkerCount:    1,
		PauseCheckWait: 2 * time.Millisecond,
		DLQHandler: func(_ context.Context, msg kafka.Message, _ error) error {
			dlqOffsets = append(dlqOffsets, msg.Offset)
			return nil
		},
	}

	handler := func(_ context.Context, msg kafka.Message) error {
		if msg.Offset == 10 {
			return errors.New("poison pill")
		}
		return nil
	}

	rec := &commitRecorder{}
	dispatcher := NewStreamDispatcher(cfg, nil, handler, rec.commit)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	dispatcher.Start(ctx)

	require.NoError(t, dispatcher.Submit(ctx, kafka.Message{Topic: "orders.events", Partition: 0, Offset: 10}))
	require.NoError(t, dispatcher.Submit(ctx, kafka.Message{Topic: "orders.events", Partition: 0, Offset: 11}))
	dispatcher.Stop()

	assert.Equal(t, []int64{10}, dlqOffsets)
	offsets := rec.offsetsFor("orders.events", 0)
	require.NotEmpty(t, offsets)
	assert.Equal(t, int64(11), offsets[len(offsets)-1])
}

func TestWatermarkTracker_LateEventEvaluationTable(t *testing.T) {
	base := time.Date(2026, 3, 21, 12, 0, 0, 0, time.UTC)
	tracker := NewWatermarkTracker(5 * time.Minute)

	cases := []struct {
		name           string
		offset         time.Duration
		wantOutOfOrder bool
		wantLate       bool
		wantLateness   time.Duration
	}{
		{name: "initial event establishes watermark", offset: 0, wantOutOfOrder: false, wantLate: false, wantLateness: 0},
		{name: "advancing event updates max timestamp", offset: 10 * time.Minute, wantOutOfOrder: false, wantLate: false, wantLateness: 0},
		{name: "slightly out-of-order within 5m lateness window", offset: 7 * time.Minute, wantOutOfOrder: true, wantLate: false, wantLateness: 3 * time.Minute},
		{name: "exact watermark boundary (10m - 5m = 5m)", offset: 5 * time.Minute, wantOutOfOrder: true, wantLate: false, wantLateness: 5 * time.Minute},
		{name: "event older than watermark (>5m late)", offset: 2 * time.Minute, wantOutOfOrder: true, wantLate: true, wantLateness: 8 * time.Minute},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			dec := tracker.Observe("orders.events:0", base.Add(tc.offset))
			assert.Equal(t, tc.wantOutOfOrder, dec.OutOfOrder)
			assert.Equal(t, tc.wantLate, dec.Late)
			assert.Equal(t, tc.wantLateness, dec.Lateness)
		})
	}

	wm, ok := tracker.Watermark("orders.events:0")
	require.True(t, ok)
	assert.Equal(t, base.Add(5*time.Minute), wm)
	assert.Equal(t, int64(1), tracker.LateCount("orders.events:0"))
	assert.Equal(t, int64(1), tracker.TotalLateCount())
}

func TestStreamDispatcher_LateEventDropAdvancesCommitWithoutCallingHandler(t *testing.T) {
	var handled atomic.Int64
	cfg := StreamConfig{
		BufferSize:      16,
		HighWatermark:   12,
		LowWatermark:    4,
		WorkerCount:     1,
		PauseCheckWait:  2 * time.Millisecond,
		AllowedLateness: 2 * time.Minute,
		DropLateEvents:  true,
	}

	rec := &commitRecorder{}
	dispatcher := NewStreamDispatcher(cfg, nil, func(context.Context, kafka.Message) error {
		handled.Add(1)
		return nil
	}, rec.commit)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	dispatcher.Start(ctx)

	require.NoError(t, dispatcher.Submit(ctx, kafka.Message{
		Topic:     "orders.events",
		Partition: 0,
		Offset:    1,
		Value:     []byte(`{"id":"evt-1","timestamp":"2026-03-21T12:10:00Z"}`),
	}))
	require.NoError(t, dispatcher.Submit(ctx, kafka.Message{
		Topic:     "orders.events",
		Partition: 0,
		Offset:    2,
		Value:     []byte(`{"id":"evt-2","timestamp":"2026-03-21T12:01:00Z"}`),
	}))
	dispatcher.Stop()

	assert.Equal(t, int64(1), handled.Load(), "late event past AllowedLateness must be dropped when DropLateEvents=true")
	assert.Equal(t, int64(1), dispatcher.LateEventCount())
	offsets := rec.offsetsFor("orders.events", 0)
	require.NotEmpty(t, offsets)
	assert.Equal(t, int64(2), offsets[len(offsets)-1], "dropped late event must still advance offset commit")
}

func TestRiderProcessor_OutOfOrderEventsDoNotRegressRiderStateOrLocation(t *testing.T) {
	srv := startFakeRESPServer(t)
	rdb := redis.NewClient(&redis.Options{Addr: srv.addr(), Protocol: 2})
	t.Cleanup(func() { _ = rdb.Close() })

	proc := NewRiderProcessor(rdb, nil)
	ctx := context.Background()
	t1 := time.Date(2026, 3, 21, 12, 10, 0, 0, time.UTC)
	t0 := t1.Add(-8 * time.Minute)

	require.NoError(t, proc.ProcessRiderEvent(ctx, RiderEvent{
		EventType: "RiderAssigned",
		RiderID:   "rider-late-1",
		ZoneID:    "zone-koramangala",
		Timestamp: t1,
	}))
	require.NoError(t, proc.ProcessRiderEvent(ctx, RiderEvent{
		EventType: "RiderOffline",
		RiderID:   "rider-late-1",
		ZoneID:    "zone-old",
		Timestamp: t0,
	}))

	proc.mu.RLock()
	st := proc.states["rider-late-1"]
	require.NotNil(t, st)
	assert.Equal(t, "active", st.Status, "stale RiderOffline must not overwrite newer RiderAssigned state")
	assert.Equal(t, "zone-koramangala", st.ZoneID)
	proc.mu.RUnlock()
	assert.Equal(t, int64(1), proc.WatermarkTracker().LateCount("rider.events"))

	cmdsBefore := len(srv.commands())
	require.NoError(t, proc.ProcessLocationUpdate(ctx, LocationUpdate{
		RiderID:   "rider-late-1",
		ZoneID:    "zone-koramangala",
		Latitude:  12.9352,
		Longitude: 77.6245,
		Timestamp: t1,
	}))
	cmdsAfterFresh := len(srv.commands())
	assert.Greater(t, cmdsAfterFresh, cmdsBefore)

	require.NoError(t, proc.ProcessLocationUpdate(ctx, LocationUpdate{
		RiderID:   "rider-late-1",
		ZoneID:    "zone-koramangala",
		Latitude:  12.9000,
		Longitude: 77.5000,
		Timestamp: t0,
	}))
	assert.Equal(t, cmdsAfterFresh, len(srv.commands()), "stale LocationUpdate must not overwrite Redis position")
}

func TestWatermarkTracker_MaxFutureSkewAndConcurrentObserve(t *testing.T) {
	now := time.Now().UTC().Truncate(time.Second)
	tracker := NewWatermarkTrackerWithSkew(5*time.Minute, 5*time.Minute)
	tracker.nowFn = func() time.Time { return now }

	bad := tracker.Observe("orders.events:0", now.Add(2*time.Hour))
	assert.True(t, bad.FutureSkewed)
	_, ok := tracker.Watermark("orders.events:0")
	assert.False(t, ok, "initial future-skewed event must not initialize watermark")

	d1 := tracker.Observe("orders.events:0", now)
	assert.False(t, d1.FutureSkewed)
	assert.False(t, d1.Late)

	skewed := tracker.Observe("orders.events:0", now.Add(3*time.Hour))
	assert.True(t, skewed.FutureSkewed)
	assert.Equal(t, now, skewed.MaxEventTime)

	d2 := tracker.Observe("orders.events:0", now.Add(time.Minute))
	assert.False(t, d2.FutureSkewed)
	assert.False(t, d2.Late)

	var wg sync.WaitGroup
	for i := 0; i < 16; i++ {
		wg.Add(1)
		go func(offset int) {
			defer wg.Done()
			tracker.Observe("concurrent.stream:0", now.Add(-time.Duration(offset)*time.Second))
		}(i)
	}
	wg.Wait()
	maxTS, ok := tracker.MaxObserved("concurrent.stream:0")
	require.True(t, ok)
	assert.Equal(t, now, maxTS)
}

func TestStreamDispatcher_FutureSkewedEventDoesNotPoisonTraffic(t *testing.T) {
	now := time.Now().UTC().Truncate(time.Second)
	var handled atomic.Int64
	cfg := StreamConfig{
		BufferSize:      16,
		HighWatermark:   12,
		LowWatermark:    4,
		WorkerCount:     1,
		PauseCheckWait:  2 * time.Millisecond,
		AllowedLateness: 2 * time.Minute,
		MaxFutureSkew:   5 * time.Minute,
		DropLateEvents:  true,
	}
	rec := &commitRecorder{}
	dispatcher := NewStreamDispatcher(cfg, nil, func(context.Context, kafka.Message) error {
		handled.Add(1)
		return nil
	}, rec.commit)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	dispatcher.Start(ctx)

	payload := func(id string, ts time.Time) []byte {
		b, _ := json.Marshal(map[string]any{"id": id, "timestamp": ts.Format(time.RFC3339)})
		return b
	}
	require.NoError(t, dispatcher.Submit(ctx, kafka.Message{
		Topic: "orders.events", Partition: 0, Offset: 1,
		Value: payload("future-poison", now.Add(24*time.Hour)),
	}))
	require.NoError(t, dispatcher.Submit(ctx, kafka.Message{
		Topic: "orders.events", Partition: 0, Offset: 2,
		Value: payload("legit-now", now),
	}))
	dispatcher.Stop()

	assert.Equal(t, int64(2), handled.Load())
	assert.Equal(t, int64(0), dispatcher.LateEventCount())
}

func TestRiderProcessor_LastLocationAtBoundedByTTLAndCapacity(t *testing.T) {
	now := time.Now().UTC()
	proc := NewRiderProcessor(nil, nil)
	proc.locationStateTTL = 5 * time.Minute
	proc.maxLocationStates = 2
	proc.nowFn = func() time.Time { return now }

	ctx := context.Background()
	require.NoError(t, proc.ProcessLocationUpdate(ctx, LocationUpdate{RiderID: "r1", Timestamp: now}))
	now = now.Add(time.Second)
	require.NoError(t, proc.ProcessLocationUpdate(ctx, LocationUpdate{RiderID: "r2", Timestamp: now}))
	now = now.Add(time.Second)
	require.NoError(t, proc.ProcessLocationUpdate(ctx, LocationUpdate{RiderID: "r3", Timestamp: now}))

	proc.mu.RLock()
	assert.Len(t, proc.lastLocationAt, 2)
	_, hasR1 := proc.lastLocationAt["r1"]
	proc.mu.RUnlock()
	assert.False(t, hasR1)

	now = now.Add(10 * time.Minute)
	require.NoError(t, proc.ProcessLocationUpdate(ctx, LocationUpdate{RiderID: "r4", Timestamp: now}))

	proc.mu.RLock()
	assert.Len(t, proc.lastLocationAt, 1)
	_, hasR4 := proc.lastLocationAt["r4"]
	proc.mu.RUnlock()
	assert.True(t, hasR4)
}
