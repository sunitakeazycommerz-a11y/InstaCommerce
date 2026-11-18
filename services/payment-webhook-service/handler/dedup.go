package handler

import (
	"context"
	"sync"
	"time"

	"github.com/redis/go-redis/v9"
)

const (
	defaultDedupeTTL     = 24 * time.Hour
	defaultDedupeMaxSize = 10000
	defaultCleanupWindow = time.Minute
	defaultRedisTimeout  = 50 * time.Millisecond
	defaultRedisPrefix   = "payment-webhook:event:"
)

// DedupeStore abstracts context-aware idempotency persistence for webhook event IDs.
// CheckAndMark returns duplicate=true when eventID was already recorded within its TTL.
type DedupeStore interface {
	Check(ctx context.Context, eventID string) (bool, error)
	CheckAndMark(ctx context.Context, eventID string) (duplicate bool, err error)
	Remove(ctx context.Context, eventID string) error
	Close() error
}

// InMemoryIdempotencyStore prevents duplicate processing of webhook events in memory
// using a bounded TTL map with periodic reaping and oldest-entry eviction.
type InMemoryIdempotencyStore struct {
	mu        sync.RWMutex
	seen      map[string]time.Time
	ttl       time.Duration
	maxSize   int
	done      chan struct{}
	closeOnce sync.Once
}

// NewInMemoryIdempotencyStore creates an InMemoryIdempotencyStore implementing DedupeStore.
func NewInMemoryIdempotencyStore(ttl time.Duration, maxSize int, cleanupInterval time.Duration) *InMemoryIdempotencyStore {
	if ttl <= 0 {
		ttl = defaultDedupeTTL
	}
	if maxSize <= 0 {
		maxSize = defaultDedupeMaxSize
	}
	if cleanupInterval <= 0 {
		cleanupInterval = defaultCleanupWindow
	}
	s := &InMemoryIdempotencyStore{
		seen:    make(map[string]time.Time, maxSize),
		ttl:     ttl,
		maxSize: maxSize,
		done:    make(chan struct{}),
	}
	go s.cleanupLoop(cleanupInterval)
	return s
}

// Check returns true if eventID is present and unexpired.
func (s *InMemoryIdempotencyStore) Check(_ context.Context, eventID string) (bool, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	expiry, ok := s.seen[eventID]
	if !ok {
		return false, nil
	}
	return time.Now().Before(expiry), nil
}

// Mark records eventID until ttl elapses.
func (s *InMemoryIdempotencyStore) Mark(eventID string) {
	s.mu.Lock()
	defer s.mu.Unlock()

	if len(s.seen) >= s.maxSize {
		s.evictOldestLocked()
	}
	s.seen[eventID] = time.Now().Add(s.ttl)
}

// CheckAndMark atomically checks whether eventID is active within the TTL window.
// It returns (true, nil) if already seen (duplicate), or marks it and returns (false, nil).
func (s *InMemoryIdempotencyStore) CheckAndMark(_ context.Context, eventID string) (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	expiry, ok := s.seen[eventID]
	if ok && time.Now().Before(expiry) {
		return true, nil
	}

	if len(s.seen) >= s.maxSize {
		s.evictOldestLocked()
	}
	s.seen[eventID] = time.Now().Add(s.ttl)
	return false, nil
}

// Remove deletes eventID so failed downstream publishes can be retried.
func (s *InMemoryIdempotencyStore) Remove(_ context.Context, eventID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.seen, eventID)
	return nil
}

// Close stops the background cleanup goroutine.
func (s *InMemoryIdempotencyStore) Close() error {
	s.closeOnce.Do(func() {
		close(s.done)
	})
	return nil
}

func (s *InMemoryIdempotencyStore) cleanupLoop(interval time.Duration) {
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

func (s *InMemoryIdempotencyStore) removeExpired() {
	now := time.Now()
	s.mu.Lock()
	defer s.mu.Unlock()

	for id, expiry := range s.seen {
		if now.After(expiry) {
			delete(s.seen, id)
		}
	}
}

func (s *InMemoryIdempotencyStore) evictOldestLocked() {
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

// RedisDedupeConfig configures Redis-backed cross-instance webhook deduplication.
type RedisDedupeConfig struct {
	Addr     string
	Password string
	DB       int
	TTL      time.Duration
	Timeout  time.Duration
	Prefix   string
}

// RedisIdempotencyStore provides cross-instance webhook deduplication backed by Redis SET NX.
type RedisIdempotencyStore struct {
	client  *redis.Client
	ttl     time.Duration
	prefix  string
	timeout time.Duration
}

// NewRedisIdempotencyStore connects to Redis, verifies connectivity via PING, and returns a DedupeStore.
func NewRedisIdempotencyStore(cfg RedisDedupeConfig) (*RedisIdempotencyStore, error) {
	timeout := cfg.Timeout
	if timeout <= 0 {
		timeout = defaultRedisTimeout
	}
	ttl := cfg.TTL
	if ttl <= 0 {
		ttl = defaultDedupeTTL
	}
	prefix := cfg.Prefix
	if prefix == "" {
		prefix = defaultRedisPrefix
	}
	client := redis.NewClient(&redis.Options{
		Addr:         cfg.Addr,
		Password:     cfg.Password,
		DB:           cfg.DB,
		DialTimeout:  timeout,
		ReadTimeout:  timeout,
		WriteTimeout: timeout,
	})
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	if err := client.Ping(ctx).Err(); err != nil {
		_ = client.Close()
		return nil, err
	}
	return &RedisIdempotencyStore{
		client:  client,
		ttl:     ttl,
		prefix:  prefix,
		timeout: timeout,
	}, nil
}

// NewRedisIdempotencyStoreWithClient constructs a RedisIdempotencyStore around an existing client.
func NewRedisIdempotencyStoreWithClient(client *redis.Client, ttl, timeout time.Duration) *RedisIdempotencyStore {
	if ttl <= 0 {
		ttl = defaultDedupeTTL
	}
	if timeout <= 0 {
		timeout = defaultRedisTimeout
	}
	return &RedisIdempotencyStore{
		client:  client,
		ttl:     ttl,
		prefix:  defaultRedisPrefix,
		timeout: timeout,
	}
}

// Client returns the underlying Redis client (e.g., for readiness/health checks).
func (d *RedisIdempotencyStore) Client() *redis.Client {
	return d.client
}

// Check returns true if eventID already exists in Redis.
func (d *RedisIdempotencyStore) Check(ctx context.Context, eventID string) (bool, error) {
	checkCtx, cancel := context.WithTimeout(ctx, d.timeout)
	defer cancel()
	n, err := d.client.Exists(checkCtx, d.prefix+eventID).Result()
	if err != nil {
		return false, err
	}
	return n > 0, nil
}

// CheckAndMark atomically records eventID via Redis SET NX EX.
// Returns duplicate=true if the key already existed, or duplicate=false if newly acquired.
func (d *RedisIdempotencyStore) CheckAndMark(ctx context.Context, eventID string) (bool, error) {
	markCtx, cancel := context.WithTimeout(ctx, d.timeout)
	defer cancel()
	acquired, err := d.client.SetNX(markCtx, d.prefix+eventID, "1", d.ttl).Result()
	if err != nil {
		return false, err
	}
	return !acquired, nil
}

// Remove deletes eventID from Redis so failed downstream publishes can be retried.
func (d *RedisIdempotencyStore) Remove(ctx context.Context, eventID string) error {
	rmCtx, cancel := context.WithTimeout(ctx, d.timeout)
	defer cancel()
	return d.client.Del(rmCtx, d.prefix+eventID).Err()
}

// Close closes the underlying Redis client.
func (d *RedisIdempotencyStore) Close() error {
	return d.client.Close()
}

// IdempotencyStore preserves the synchronous in-memory API used by legacy callers/tests
// while delegating storage to InMemoryIdempotencyStore.
type IdempotencyStore struct {
	inner *InMemoryIdempotencyStore
}

// NewIdempotencyStore creates an IdempotencyStore that keeps event IDs for ttl
// and evicts the oldest entries when maxSize is exceeded.
func NewIdempotencyStore(ttl time.Duration, maxSize int, cleanupInterval time.Duration) *IdempotencyStore {
	return &IdempotencyStore{
		inner: NewInMemoryIdempotencyStore(ttl, maxSize, cleanupInterval),
	}
}

// AsDedupeStore exposes the underlying DedupeStore implementation.
func (s *IdempotencyStore) AsDedupeStore() DedupeStore {
	return s.inner
}

// IsDuplicate returns true if eventID has been seen within the TTL window.
func (s *IdempotencyStore) IsDuplicate(eventID string) bool {
	dup, _ := s.inner.Check(context.Background(), eventID)
	return dup
}

// Mark records eventID so that subsequent calls to IsDuplicate return true until TTL expires.
func (s *IdempotencyStore) Mark(eventID string) {
	s.inner.Mark(eventID)
}

// CheckAndMark atomically checks if eventID has been seen within the TTL window,
// and if not, marks it and returns false (not duplicate).
func (s *IdempotencyStore) CheckAndMark(eventID string) bool {
	dup, _ := s.inner.CheckAndMark(context.Background(), eventID)
	return dup
}

// Remove deletes eventID from the store.
func (s *IdempotencyStore) Remove(eventID string) {
	_ = s.inner.Remove(context.Background(), eventID)
}

// Close stops the background cleanup goroutine.
func (s *IdempotencyStore) Close() {
	_ = s.inner.Close()
}
