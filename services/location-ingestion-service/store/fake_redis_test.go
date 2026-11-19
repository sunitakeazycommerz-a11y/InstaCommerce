package store

import (
	"bufio"
	"fmt"
	"io"
	"net"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"testing"

	"github.com/redis/go-redis/v9"
	"github.com/stretchr/testify/require"
)

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

	t.Cleanup(func() { srv.close() })
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
	case "CLIENT", "OK":
		return "+OK\r\n", false
	case "QUIT":
		return "+OK\r\n", true
	case "EVALSHA":
		return "-NOSCRIPT No matching script. Please use EVAL.\r\n", false
	case "EVAL":
		return s.handleEvalLocked(args), false
	case "HGETALL":
		return s.handleHGetAllLocked(args), false
	case "SMEMBERS":
		return s.handleSMembersLocked(args), false
	case "SCAN":
		return s.handleScanLocked(args), false
	default:
		return "+OK\r\n", false
	}
}

func (s *fakeRedisServer) handleHGetAllLocked(args []string) string {
	if len(args) < 1 {
		return "-ERR wrong number of arguments\r\n"
	}
	h := s.hashes[args[0]]
	if len(h) == 0 {
		return "*0\r\n"
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
	return b.String()
}

func (s *fakeRedisServer) handleSMembersLocked(args []string) string {
	if len(args) < 1 {
		return "-ERR wrong number of arguments\r\n"
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
	return b.String()
}

func (s *fakeRedisServer) handleScanLocked(args []string) string {
	pattern := "*"
	for i := 1; i+1 < len(args); i += 2 {
		if strings.EqualFold(args[i], "MATCH") {
			pattern = args[i+1]
		}
	}
	var matched []string
	for k := range s.hashes {
		if ok, _ := filepath.Match(pattern, k); ok {
			matched = append(matched, k)
		}
	}
	sort.Strings(matched)
	var b strings.Builder
	fmt.Fprintf(&b, "*2\r\n$1\r\n0\r\n*%d\r\n", len(matched))
	for _, k := range matched {
		fmt.Fprintf(&b, "$%d\r\n%s\r\n", len(k), k)
	}
	return b.String()
}

func (s *fakeRedisServer) handleEvalLocked(args []string) string {
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
	client := redis.NewClient(&redis.Options{Addr: addr, Protocol: 2})
	t.Cleanup(func() { _ = client.Close() })
	return client
}
