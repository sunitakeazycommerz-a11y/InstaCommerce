package handler

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/instacommerce/dispatch-optimizer-service/optimizer"
	"github.com/instacommerce/go-shared/pkg/correlation"
	"github.com/redis/go-redis/v9"
	"github.com/segmentio/kafka-go"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestHandleAssignV2_JavaDispatchOptimizerClientContract(t *testing.T) {
	// Exact payload shape built by services/rider-fleet-service DispatchOptimizerClient.java
	javaPayload := `{
		"riders": [
			{
				"id": "rider-101",
				"position": {"lat": 12.9716, "lng": 77.5946},
				"active_orders": 0,
				"vehicle_type": "SCOOTER",
				"is_available": true,
				"total_deliveries": 142,
				"avg_rating": 4.87,
				"consecutive_deliveries": 2,
				"avg_speed_kmh": 24.5,
				"battery_percent": 82.0,
				"zone": null
			}
		],
		"orders": [
			{
				"id": "order-901",
				"position": {"lat": 12.9760, "lng": 77.5993}
			}
		]
	}`

	solver := optimizer.NewSolver(optimizer.SolverConfig{})
	h := HandleAssignV2(solver, slog.New(slog.NewTextHandler(io.Discard, nil)))

	req := httptest.NewRequest(http.MethodPost, "/v2/optimize/assign", bytes.NewBufferString(javaPayload))
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)

	require.Equal(t, http.StatusOK, rec.Code, "body=%s", rec.Body.String())

	var resp optimizer.SolverResult
	require.NoError(t, json.Unmarshal(rec.Body.Bytes(), &resp))
	require.Len(t, resp.Assignments, 1)
	assert.Equal(t, "rider-101", resp.Assignments[0].RiderID)
	assert.Equal(t, []string{"order-901"}, resp.Assignments[0].OrderIDs)
	assert.Empty(t, resp.UnassignedOrders)
}

func TestHandleAssignV2_RejectsNegativeAvgRating(t *testing.T) {
	payload := `{
		"riders": [
			{
				"id": "rider-1",
				"position": {"lat": 12.9716, "lng": 77.5946},
				"is_available": true,
				"avg_rating": -0.5
			}
		],
		"orders": [
			{"id": "order-1", "position": {"lat": 12.9720, "lng": 77.5950}}
		]
	}`

	solver := optimizer.NewSolver(optimizer.SolverConfig{})
	h := HandleAssignV2(solver, slog.New(slog.NewTextHandler(io.Discard, nil)))

	req := httptest.NewRequest(http.MethodPost, "/v2/optimize/assign", bytes.NewBufferString(payload))
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)

	assert.Equal(t, http.StatusBadRequest, rec.Code)
}

type fakeKafkaWriter struct {
	messages []kafka.Message
	err      error
	closed   bool
}

func (f *fakeKafkaWriter) WriteMessages(_ context.Context, msgs ...kafka.Message) error {
	if f.err != nil {
		return f.err
	}
	f.messages = append(f.messages, msgs...)
	return nil
}

func (f *fakeKafkaWriter) Close() error {
	f.closed = true
	return nil
}

func TestKafkaEventPublisher_PublishesTopicKeyPayloadAndCorrelation(t *testing.T) {
	fw := &fakeKafkaWriter{}
	pub := &KafkaEventPublisher{writer: fw, logger: slog.New(slog.NewTextHandler(io.Discard, nil))}

	ctx := context.WithValue(context.Background(), correlation.ContextKey, "corr-dispatch-42")
	err := pub.Publish(ctx, "rider.events", "rider-7", map[string]any{"eventType": "RiderAssigned"})
	require.NoError(t, err)
	require.Len(t, fw.messages, 1)
	assert.Equal(t, "rider.events", fw.messages[0].Topic)
	assert.Equal(t, []byte("rider-7"), fw.messages[0].Key)
	assert.Contains(t, string(fw.messages[0].Value), "RiderAssigned")
	require.Len(t, fw.messages[0].Headers, 1)
	assert.Equal(t, "correlation_id", fw.messages[0].Headers[0].Key)
	assert.Equal(t, "corr-dispatch-42", string(fw.messages[0].Headers[0].Value))

	fw.err = errors.New("broker unavailable")
	err = pub.Publish(ctx, "rider.events", "rider-7", map[string]any{"eventType": "RiderAssigned"})
	require.Error(t, err)
	require.NoError(t, pub.Close())
	assert.True(t, fw.closed)
}

type fakeRedisLockClient struct {
	keys        map[string]string
	setErr      error
	evalErr     error
	evalCalls   int
	lastScript  string
	lastLockKey string
}

func (f *fakeRedisLockClient) SetNX(ctx context.Context, key string, value any, _ time.Duration) *redis.BoolCmd {
	cmd := redis.NewBoolCmd(ctx, "set", key, value)
	if f.setErr != nil {
		cmd.SetErr(f.setErr)
		return cmd
	}
	if _, exists := f.keys[key]; exists {
		cmd.SetVal(false)
		return cmd
	}
	f.keys[key] = value.(string)
	cmd.SetVal(true)
	return cmd
}

func (f *fakeRedisLockClient) Eval(ctx context.Context, script string, keys []string, args ...any) *redis.Cmd {
	f.evalCalls++
	f.lastScript = script
	cmd := redis.NewCmd(ctx)
	if f.evalErr != nil {
		cmd.SetErr(f.evalErr)
		return cmd
	}
	if len(keys) == 1 && len(args) == 1 {
		f.lastLockKey = keys[0]
		if f.keys[keys[0]] == args[0].(string) {
			delete(f.keys, keys[0])
			cmd.SetVal(int64(1))
			return cmd
		}
	}
	cmd.SetVal(int64(0))
	return cmd
}

func TestRedisRiderMutex_LockContentionAndTokenSafeUnlock(t *testing.T) {
	client := &fakeRedisLockClient{keys: make(map[string]string)}
	locker := NewRedisRiderMutex(client, slog.New(slog.NewTextHandler(io.Discard, nil)))

	unlock, err := locker.Lock(context.Background(), "rider-9", 30*time.Second)
	require.NoError(t, err)
	require.NotEmpty(t, client.keys["dispatch:rider:lock:rider-9"])

	_, err = locker.Lock(context.Background(), "rider-9", 30*time.Second)
	require.Error(t, err)
	assert.ErrorIs(t, err, ErrRiderAlreadyLocked)

	require.NoError(t, unlock())
	// Unlock twice should be a no-op via sync.Once
	require.NoError(t, unlock())
	assert.Equal(t, 1, client.evalCalls)
	assert.Empty(t, client.keys)

	unlock2, err := locker.Lock(context.Background(), "rider-9", 30*time.Second)
	require.NoError(t, err)
	require.NoError(t, unlock2())
}

func TestEnvFallbacks_UseNoopPublisherAndInMemoryMutexWhenUnset(t *testing.T) {
	t.Setenv("KAFKA_BROKERS", "")
	t.Setenv("REDIS_ADDR", "")
	t.Setenv("REDIS_HOST", "")

	pub, brokers, closePub := NewEventPublisherFromEnv(slog.New(slog.NewTextHandler(io.Discard, nil)))
	defer func() { _ = closePub() }()
	assert.Empty(t, brokers)
	_, ok := pub.(*NoopEventPublisher)
	assert.True(t, ok)

	locker, redisClient, closeRedis := NewRiderMutexFromEnv(slog.New(slog.NewTextHandler(io.Discard, nil)))
	defer func() { _ = closeRedis() }()
	assert.Nil(t, redisClient)
	unlock, err := locker.Lock(context.Background(), "rider-local", time.Second)
	require.NoError(t, err)
	require.NoError(t, unlock())
}
