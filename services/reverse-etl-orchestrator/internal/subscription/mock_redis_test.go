package subscription

import (
	"context"
	"database/sql/driver"
	"fmt"
	"sync"
	"time"

	"github.com/redis/go-redis/v9"
)

type mockRedisClient struct {
	mu        sync.RWMutex
	hashes    map[string]map[string]any
	values    map[string]string
	ttls      map[string]time.Duration
	expireErr error
}

func newMockRedisClient() *mockRedisClient {
	return &mockRedisClient{
		hashes: make(map[string]map[string]any),
		values: make(map[string]string),
		ttls:   make(map[string]time.Duration),
	}
}

func (m *mockRedisClient) HSet(ctx context.Context, key string, values ...any) *redis.IntCmd {
	m.mu.Lock()
	defer m.mu.Unlock()

	if _, ok := m.hashes[key]; !ok {
		m.hashes[key] = make(map[string]any)
	}

	var count int64
	for i := 0; i < len(values); i += 2 {
		if i+1 < len(values) {
			field := fmt.Sprintf("%v", values[i])
			m.hashes[key][field] = values[i+1]
			count++
		}
	}
	cmd := redis.NewIntCmd(ctx)
	cmd.SetVal(count)
	return cmd
}

func (m *mockRedisClient) Rename(ctx context.Context, key, newKey string) *redis.StatusCmd {
	m.mu.Lock()
	defer m.mu.Unlock()

	cmd := redis.NewStatusCmd(ctx)
	if hash, ok := m.hashes[key]; ok {
		m.hashes[newKey] = hash
		delete(m.hashes, key)
		cmd.SetVal("OK")
		return cmd
	}

	if val, ok := m.values[key]; ok {
		m.values[newKey] = val
		delete(m.values, key)
		if ttl, hasTTL := m.ttls[key]; hasTTL {
			m.ttls[newKey] = ttl
			delete(m.ttls, key)
		}
		cmd.SetVal("OK")
		return cmd
	}

	cmd.SetErr(redis.Nil)
	return cmd
}

func (m *mockRedisClient) Set(ctx context.Context, key string, value any, expiration time.Duration) *redis.StatusCmd {
	m.mu.Lock()
	defer m.mu.Unlock()

	m.values[key] = fmt.Sprintf("%v", value)
	if expiration > 0 {
		m.ttls[key] = expiration
	}
	cmd := redis.NewStatusCmd(ctx)
	cmd.SetVal("OK")
	return cmd
}

func (m *mockRedisClient) Get(ctx context.Context, key string) *redis.StringCmd {
	m.mu.RLock()
	defer m.mu.RUnlock()

	cmd := redis.NewStringCmd(ctx)
	if val, ok := m.values[key]; ok {
		cmd.SetVal(val)
	} else {
		cmd.SetErr(redis.Nil)
	}
	return cmd
}

func (m *mockRedisClient) Expire(ctx context.Context, key string, expiration time.Duration) *redis.BoolCmd {
	m.mu.Lock()
	defer m.mu.Unlock()

	cmd := redis.NewBoolCmd(ctx)
	if m.expireErr != nil {
		cmd.SetErr(m.expireErr)
		return cmd
	}
	if _, ok := m.values[key]; ok {
		m.ttls[key] = expiration
		cmd.SetVal(true)
		return cmd
	}
	if _, ok := m.hashes[key]; ok {
		m.ttls[key] = expiration
		cmd.SetVal(true)
		return cmd
	}
	cmd.SetVal(false)
	return cmd
}

func (m *mockRedisClient) Del(ctx context.Context, keys ...string) *redis.IntCmd {
	m.mu.Lock()
	defer m.mu.Unlock()

	var deleted int64
	for _, k := range keys {
		if _, ok := m.values[k]; ok {
			delete(m.values, k)
			deleted++
		}
		if _, ok := m.hashes[k]; ok {
			delete(m.hashes, k)
			deleted++
		}
	}
	cmd := redis.NewIntCmd(ctx)
	cmd.SetVal(deleted)
	return cmd
}

func (m *mockRedisClient) Ping(ctx context.Context) *redis.StatusCmd {
	cmd := redis.NewStatusCmd(ctx)
	cmd.SetVal("PONG")
	return cmd
}

type recordedAuditExec struct {
	query       string
	args        []driver.NamedValue
	ctxErr      error
	hasDeadline bool
}

type auditCaptureDB struct {
	mu    sync.Mutex
	execs []recordedAuditExec
}

func (a *auditCaptureDB) Connect(_ context.Context) (driver.Conn, error) { return a, nil }
func (a *auditCaptureDB) Driver() driver.Driver                          { return a }
func (a *auditCaptureDB) Open(_ string) (driver.Conn, error)             { return a, nil }
func (a *auditCaptureDB) Prepare(_ string) (driver.Stmt, error)          { return nil, driver.ErrSkip }
func (a *auditCaptureDB) Close() error                                   { return nil }
func (a *auditCaptureDB) Begin() (driver.Tx, error)                      { return nil, driver.ErrSkip }

func (a *auditCaptureDB) ExecContext(ctx context.Context, query string, args []driver.NamedValue) (driver.Result, error) {
	_, hasDeadline := ctx.Deadline()
	a.mu.Lock()
	a.execs = append(a.execs, recordedAuditExec{
		query:       query,
		args:        args,
		ctxErr:      ctx.Err(),
		hasDeadline: hasDeadline,
	})
	a.mu.Unlock()
	return driver.RowsAffected(1), nil
}
