package subscription

import (
	"context"
	"database/sql/driver"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/redis/go-redis/v9"
)

type mockRedisClient struct {
	mu            sync.RWMutex
	hashes        map[string]map[string]any
	values        map[string]string
	ttls          map[string]time.Duration
	tempKeyTTLs   map[string]time.Duration
	expireErr     error
	evalSwapHook  func(attempt int, tempKey, targetKey string) error
	swapEvalCalls int
}

func newMockRedisClient() *mockRedisClient {
	return &mockRedisClient{
		hashes:      make(map[string]map[string]any),
		values:      make(map[string]string),
		ttls:        make(map[string]time.Duration),
		tempKeyTTLs: make(map[string]time.Duration),
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
		if ttl, hasTTL := m.ttls[key]; hasTTL {
			m.ttls[newKey] = ttl
			delete(m.ttls, key)
		}
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

func parseEvalTTL(raw any) time.Duration {
	switch v := raw.(type) {
	case int64:
		return time.Duration(v) * time.Millisecond
	case int:
		return time.Duration(v) * time.Millisecond
	case string:
		ms, _ := strconv.ParseInt(v, 10, 64)
		return time.Duration(ms) * time.Millisecond
	default:
		return 0
	}
}

func (m *mockRedisClient) Eval(ctx context.Context, script string, keys []string, args ...any) *redis.Cmd {
	m.mu.Lock()
	defer m.mu.Unlock()

	cmd := redis.NewCmd(ctx)
	if strings.Contains(script, "HSET") && len(keys) >= 1 && len(args) >= 1 {
		key := keys[0]
		ttl := parseEvalTTL(args[0])
		delete(m.hashes, key)
		m.hashes[key] = make(map[string]any)
		for i := 1; i+1 < len(args); i += 2 {
			field := fmt.Sprintf("%v", args[i])
			m.hashes[key][field] = args[i+1]
		}
		m.ttls[key] = ttl
		m.tempKeyTTLs[key] = ttl
		cmd.SetVal(int64(1))
		return cmd
	}

	if strings.Contains(script, "RENAME") && len(keys) >= 2 && len(args) >= 1 {
		m.swapEvalCalls++
		attempt := m.swapEvalCalls
		tempKey, targetKey := keys[0], keys[1]
		ttl := parseEvalTTL(args[0])
		if m.expireErr != nil {
			cmd.SetErr(m.expireErr)
			return cmd
		}
		var hookErr error
		if m.evalSwapHook != nil {
			hookErr = m.evalSwapHook(attempt, tempKey, targetKey)
		}
		if hookErr != nil && !errors.Is(hookErr, context.DeadlineExceeded) {
			cmd.SetErr(hookErr)
			return cmd
		}
		if hash, ok := m.hashes[tempKey]; ok {
			m.hashes[targetKey] = hash
			delete(m.hashes, tempKey)
			delete(m.ttls, tempKey)
			m.ttls[targetKey] = ttl
			if hookErr != nil {
				cmd.SetErr(hookErr)
				return cmd
			}
			cmd.SetVal(int64(1))
			return cmd
		}
		if val, ok := m.values[tempKey]; ok {
			m.values[targetKey] = val
			delete(m.values, tempKey)
			delete(m.ttls, tempKey)
			m.ttls[targetKey] = ttl
			if hookErr != nil {
				cmd.SetErr(hookErr)
				return cmd
			}
			cmd.SetVal(int64(1))
			return cmd
		}
		if _, ok := m.hashes[targetKey]; ok {
			m.ttls[targetKey] = ttl
			cmd.SetVal(int64(2))
			return cmd
		}
		if _, ok := m.values[targetKey]; ok {
			m.ttls[targetKey] = ttl
			cmd.SetVal(int64(2))
			return cmd
		}
		cmd.SetErr(errors.New("ERR no such key"))
		return cmd
	}

	cmd.SetErr(errors.New("unsupported mock lua script"))
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
