package handler

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	sharedconfig "github.com/instacommerce/go-shared/pkg/config"
	"github.com/redis/go-redis/v9"
)

const unlockScript = `
if redis.call("get", KEYS[1]) == ARGV[1] then
	return redis.call("del", KEYS[1])
else
	return 0
end
`

type redisLockClient interface {
	SetNX(ctx context.Context, key string, value any, expiration time.Duration) *redis.BoolCmd
	Eval(ctx context.Context, script string, keys []string, args ...any) *redis.Cmd
}

// RedisRiderMutex provides distributed rider locking backed by Redis SET NX PX.
type RedisRiderMutex struct {
	client    redisLockClient
	keyPrefix string
	logger    *slog.Logger
	seq       atomic.Uint64
}

// NewRedisRiderMutex creates a Redis-backed RiderMutex.
func NewRedisRiderMutex(client redisLockClient, logger *slog.Logger) *RedisRiderMutex {
	return &RedisRiderMutex{
		client:    client,
		keyPrefix: "dispatch:rider:lock:",
		logger:    logger,
	}
}

func (m *RedisRiderMutex) Lock(ctx context.Context, riderID string, ttl time.Duration) (func() error, error) {
	if m == nil || m.client == nil {
		return nil, errors.New("redis rider mutex is not initialized")
	}
	if strings.TrimSpace(riderID) == "" {
		return nil, errors.New("rider id is required")
	}
	if ttl <= 0 {
		ttl = DefaultRiderLockTTL
	}

	token := m.nextToken()
	key := m.keyPrefix + riderID
	acquired, err := m.client.SetNX(ctx, key, token, ttl).Result()
	if err != nil {
		return nil, fmt.Errorf("acquire rider lock %s: %w", riderID, err)
	}
	if !acquired {
		return nil, fmt.Errorf("rider %s: %w", riderID, ErrRiderAlreadyLocked)
	}

	var once sync.Once
	var unlockErr error
	return func() error {
		once.Do(func() {
			unlockCtx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
			defer cancel()
			err := m.client.Eval(unlockCtx, unlockScript, []string{key}, token).Err()
			if err != nil && !errors.Is(err, redis.Nil) {
				unlockErr = fmt.Errorf("release rider lock %s: %w", riderID, err)
				if m.logger != nil {
					m.logger.Warn("failed to release redis rider lock", "rider_id", riderID, "error", unlockErr)
				}
			}
		})
		return unlockErr
	}, nil
}

func (m *RedisRiderMutex) nextToken() string {
	var raw [8]byte
	if _, err := rand.Read(raw[:]); err == nil {
		return fmt.Sprintf("%s-%d", hex.EncodeToString(raw[:]), m.seq.Add(1))
	}
	return fmt.Sprintf("%d-%d", time.Now().UnixNano(), m.seq.Add(1))
}

// NewRiderMutexFromEnv builds a RedisRiderMutex when REDIS_ADDR (or REDIS_HOST) is configured,
// falling back to an in-memory mutex for single-instance local runs.
func NewRiderMutexFromEnv(logger *slog.Logger) (RiderMutex, *redis.Client, func() error) {
	addr := strings.TrimSpace(os.Getenv("REDIS_ADDR"))
	if addr == "" {
		host := strings.TrimSpace(os.Getenv("REDIS_HOST"))
		if host != "" {
			port := sharedconfig.GetEnvTrimmed("REDIS_PORT", "6379")
			addr = net.JoinHostPort(host, port)
		}
	}
	if addr == "" {
		if logger != nil {
			logger.Info("REDIS_ADDR unset; using in-memory rider mutex")
		}
		return NewInMemoryRiderMutex(), nil, func() error { return nil }
	}

	db, err := sharedconfig.GetEnvIntStrict("REDIS_DB", 0)
	if err != nil {
		if logger != nil {
			logger.Warn("invalid REDIS_DB; defaulting to 0", "error", err)
		}
		db = 0
	}

	client := redis.NewClient(&redis.Options{
		Addr:     addr,
		Password: os.Getenv("REDIS_PASSWORD"),
		DB:       db,
	})
	return NewRedisRiderMutex(client, logger), client, client.Close
}
