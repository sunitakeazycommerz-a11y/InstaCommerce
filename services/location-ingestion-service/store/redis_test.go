package store

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"net"
	"sort"
	"strconv"
	"strings"
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

type fakeRedisServer struct {
	listener net.Listener
	addr     string

	mu       sync.Mutex
	hashes   map[string]map[string]string
	sets     map[string]map[string]struct{}
	commands []string
	conns    map[net.Conn]struct{}
	wg       sync.WaitGroup
}

func startFakeRedisServer(t *testing.T) *fakeRedisServer {
	t.Helper()

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	require.NoError(t, err)

	srv := &fakeRedisServer{
		listener: ln,
		addr:     ln.Addr().String(),
		hashes:   make(map[string]map[string]string),
		sets:     make(map[string]map[string]struct{}),
		conns:    make(map[net.Conn]struct{}),
	}

	srv.wg.Add(1)
	go srv.acceptLoop()

	t.Cleanup(func() {
		srv.close()
	})
	return srv
}

func (s *fakeRedisServer) close() {
	_ = s.listener.Close()
	s.mu.Lock()
	for conn := range s.conns {
		_ = conn.Close()
	}
	s.mu.Unlock()
	s.wg.Wait()
}

func (s *fakeRedisServer) acceptLoop() {
	defer s.wg.Done()
	for {
		conn, err := s.listener.Accept()
		if err != nil {
			return
		}
		s.mu.Lock()
		s.conns[conn] = struct{}{}
		s.mu.Unlock()

		s.wg.Add(1)
		go s.serveConn(conn)
	}
}

func (s *fakeRedisServer) serveConn(conn net.Conn) {
	defer s.wg.Done()
	defer func() {
		s.mu.Lock()
		delete(s.conns, conn)
		s.mu.Unlock()
		_ = conn.Close()
	}()

	reader := bufio.NewReader(conn)
	for {
		args, err := readRESPArray(reader)
		if err != nil {
			return
		}
		if len(args) == 0 {
			continue
		}

		cmd := strings.ToUpper(args[0])
		resp, quit := s.handleCommand(cmd, args[1:])
		if _, err := conn.Write([]byte(resp)); err != nil {
			return
		}
		if quit {
			return
		}
	}
}

func (s *fakeRedisServer) handleCommand(cmd string, args []string) (string, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()

	s.commands = append(s.commands, cmd)

	switch cmd {
	case "HELLO":
		return "*2\r\n$6\r\nserver\r\n$5\r\nredis\r\n", false
	case "PING":
		return "+PONG\r\n", false
	case "CLIENT":
		return "+OK\r\n", false
	case "QUIT":
		return "+OK\r\n", true
	case "EVALSHA":
		return "-NOSCRIPT No matching script. Please use EVAL.\r\n", false
	case "EVAL":
		return s.handleEvalLocked(args), false
	case "HGETALL":
		if len(args) < 1 {
			return "-ERR wrong number of arguments\r\n", false
		}
		h := s.hashes[args[0]]
		if len(h) == 0 {
			return "*0\r\n", false
		}
		keys := make([]string, 0, len(h))
		for k := range h {
			keys = append(keys, k)
		}
		sort.Strings(keys)
		var b strings.Builder
		fmt.Fprintf(&b, "*%d\r\n", len(keys)*2)
		for _, k := range keys {
			v := h[k]
			fmt.Fprintf(&b, "$%d\r\n%s\r\n$%d\r\n%s\r\n", len(k), k, len(v), v)
		}
		return b.String(), false
	case "SMEMBERS":
		if len(args) < 1 {
			return "-ERR wrong number of arguments\r\n", false
		}
		set := s.sets[args[0]]
		members := make([]string, 0, len(set))
		for m := range set {
			members = append(members, m)
		}
		sort.Strings(members)
		var b strings.Builder
		fmt.Fprintf(&b, "*%d\r\n", len(members))
		for _, m := range members {
			fmt.Fprintf(&b, "$%d\r\n%s\r\n", len(m), m)
		}
		return b.String(), false
	default:
		return "+OK\r\n", false
	}
}

func (s *fakeRedisServer) handleEvalLocked(args []string) string {
	// args = [script, numkeys, KEYS..., ARGV...]
	if len(args) < 2 {
		return "-ERR invalid eval args\r\n"
	}
	numKeys, err := strconv.Atoi(args[1])
	if err != nil || numKeys < 1 || len(args) < 2+numKeys+4 {
		return "-ERR invalid eval key/arg count\r\n"
	}
	posKey := args[2]
	argv := args[2+numKeys:]

	incomingMs, err := strconv.ParseInt(argv[0], 10, 64)
	if err != nil {
		return "-ERR invalid timestamp_ms\r\n"
	}
	// argv[1] = ttlMs
	newH3 := argv[2]
	riderID := argv[3]

	existing := s.hashes[posKey]
	if existing != nil {
		if rawExistingTs, ok := existing["timestamp_ms"]; ok && rawExistingTs != "" {
			if existingMs, parseErr := strconv.ParseInt(rawExistingTs, 10, 64); parseErr == nil && incomingMs <= existingMs {
				return fmt.Sprintf("*2\r\n:0\r\n$%d\r\n%s\r\n", len(rawExistingTs), rawExistingTs)
			}
		}
	} else {
		existing = make(map[string]string)
		s.hashes[posKey] = existing
	}

	oldH3 := existing["h3_index"]
	for i := 4; i+1 < len(argv); i += 2 {
		existing[argv[i]] = argv[i+1]
	}

	if oldH3 != "" && oldH3 != newH3 {
		if oldSet := s.sets["riders:h3:"+oldH3]; oldSet != nil {
			delete(oldSet, riderID)
		}
	}
	if newH3 != "" {
		existing["h3_index"] = newH3
		h3Key := "riders:h3:" + newH3
		if s.sets[h3Key] == nil {
			s.sets[h3Key] = make(map[string]struct{})
		}
		s.sets[h3Key][riderID] = struct{}{}
	}

	return "*2\r\n:1\r\n$0\r\n\r\n"
}

func (s *fakeRedisServer) commandList() []string {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]string, len(s.commands))
	copy(out, s.commands)
	return out
}

func readRESPArray(r *bufio.Reader) ([]string, error) {
	line, err := r.ReadString('\n')
	if err != nil {
		return nil, err
	}
	line = strings.TrimSuffix(line, "\r\n")
	if !strings.HasPrefix(line, "*") {
		return strings.Fields(line), nil
	}
	count, err := strconv.Atoi(strings.TrimPrefix(line, "*"))
	if err != nil {
		return nil, err
	}
	args := make([]string, 0, count)
	for i := 0; i < count; i++ {
		lenLine, err := r.ReadString('\n')
		if err != nil {
			return nil, err
		}
		lenLine = strings.TrimSuffix(lenLine, "\r\n")
		n, err := strconv.Atoi(strings.TrimPrefix(lenLine, "$"))
		if err != nil {
			return nil, err
		}
		buf := make([]byte, n+2)
		if _, err := io.ReadFull(r, buf); err != nil {
			return nil, err
		}
		args = append(args, string(buf[:n]))
	}
	return args, nil
}

func newTestRedisClient(t *testing.T, addr string) *redis.Client {
	t.Helper()
	client := redis.NewClient(&redis.Options{
		Addr:     addr,
		Protocol: 2,
	})
	t.Cleanup(func() {
		_ = client.Close()
	})
	return client
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
