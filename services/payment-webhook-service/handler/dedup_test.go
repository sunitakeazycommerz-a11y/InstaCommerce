package handler

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"net"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/redis/go-redis/v9"
)

type fakeDedupeRESPServer struct {
	ln   net.Listener
	mu   sync.Mutex
	data map[string]string
	done chan struct{}
}

func startFakeDedupeRESPServer(t *testing.T) *fakeDedupeRESPServer {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	s := &fakeDedupeRESPServer{
		ln:   ln,
		data: make(map[string]string),
		done: make(chan struct{}),
	}
	go func() {
		defer close(s.done)
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			go s.serve(conn)
		}
	}()
	t.Cleanup(func() {
		_ = ln.Close()
		<-s.done
	})
	return s
}

func (s *fakeDedupeRESPServer) serve(conn net.Conn) {
	defer conn.Close()
	r := bufio.NewReader(conn)
	for {
		args, err := readTestRESPArray(r)
		if err != nil || len(args) == 0 {
			return
		}
		op := strings.ToUpper(args[0])
		switch op {
		case "HELLO":
			_, _ = io.WriteString(conn, "*2\r\n$6\r\nserver\r\n$5\r\nredis\r\n")
		case "CLIENT":
			_, _ = io.WriteString(conn, "+OK\r\n")
		case "PING":
			_, _ = io.WriteString(conn, "+PONG\r\n")
		case "SET":
			// Handles SET key val [EX|PX ttl] NX
			if len(args) >= 3 {
				key, val := args[1], args[2]
				nx := false
				for _, a := range args[3:] {
					if strings.EqualFold(a, "NX") {
						nx = true
					}
				}
				s.mu.Lock()
				_, exists := s.data[key]
				if nx && exists {
					s.mu.Unlock()
					_, _ = io.WriteString(conn, "$-1\r\n")
					continue
				}
				s.data[key] = val
				s.mu.Unlock()
			}
			_, _ = io.WriteString(conn, "+OK\r\n")
		case "SETNX":
			s.mu.Lock()
			_, exists := s.data[args[1]]
			if !exists {
				s.data[args[1]] = args[2]
			}
			s.mu.Unlock()
			if exists {
				_, _ = io.WriteString(conn, ":0\r\n")
			} else {
				_, _ = io.WriteString(conn, ":1\r\n")
			}
		case "EXISTS":
			var cnt int
			s.mu.Lock()
			for _, k := range args[1:] {
				if _, ok := s.data[k]; ok {
					cnt++
				}
			}
			s.mu.Unlock()
			_, _ = fmt.Fprintf(conn, ":%d\r\n", cnt)
		case "DEL":
			var cnt int
			s.mu.Lock()
			for _, k := range args[1:] {
				if _, ok := s.data[k]; ok {
					delete(s.data, k)
					cnt++
				}
			}
			s.mu.Unlock()
			_, _ = fmt.Fprintf(conn, ":%d\r\n", cnt)
		default:
			_, _ = io.WriteString(conn, "+OK\r\n")
		}
	}
}

func readTestRESPArray(r *bufio.Reader) ([]string, error) {
	line, err := r.ReadString('\n')
	if err != nil {
		return nil, err
	}
	line = strings.TrimSuffix(strings.TrimSuffix(line, "\n"), "\r")
	if !strings.HasPrefix(line, "*") {
		return strings.Fields(line), nil
	}
	n, err := strconv.Atoi(line[1:])
	if err != nil || n < 0 {
		return nil, fmt.Errorf("invalid array %q", line)
	}
	args := make([]string, 0, n)
	for i := 0; i < n; i++ {
		hdr, err := r.ReadString('\n')
		if err != nil {
			return nil, err
		}
		hdr = strings.TrimSuffix(strings.TrimSuffix(hdr, "\n"), "\r")
		sz, err := strconv.Atoi(strings.TrimPrefix(hdr, "$"))
		if err != nil || sz < 0 {
			return nil, fmt.Errorf("invalid bulk %q", hdr)
		}
		buf := make([]byte, sz+2)
		if _, err := io.ReadFull(r, buf); err != nil {
			return nil, err
		}
		args = append(args, string(buf[:sz]))
	}
	return args, nil
}

func TestRedisIdempotencyStore_CheckCheckAndMarkRemove(t *testing.T) {
	srv := startFakeDedupeRESPServer(t)
	client := redis.NewClient(&redis.Options{Addr: srv.ln.Addr().String(), Protocol: 2})
	store := NewRedisIdempotencyStoreWithClient(client, time.Minute, time.Second)
	defer func() { _ = store.Close() }()

	ctx := context.Background()
	isDup, err := store.Check(ctx, "evt_redis_1")
	if err != nil || isDup {
		t.Fatalf("expected non-duplicate on fresh key, got isDup=%v err=%v", isDup, err)
	}

	isDup, err = store.CheckAndMark(ctx, "evt_redis_1")
	if err != nil || isDup {
		t.Fatalf("expected first CheckAndMark to acquire (isDup=false), got isDup=%v err=%v", isDup, err)
	}

	isDup, err = store.CheckAndMark(ctx, "evt_redis_1")
	if err != nil || !isDup {
		t.Fatalf("expected second CheckAndMark to report duplicate (isDup=true), got isDup=%v err=%v", isDup, err)
	}

	if err := store.Remove(ctx, "evt_redis_1"); err != nil {
		t.Fatalf("Remove failed: %v", err)
	}

	isDup, err = store.CheckAndMark(ctx, "evt_redis_1")
	if err != nil || isDup {
		t.Fatalf("expected retry after Remove to acquire, got isDup=%v err=%v", isDup, err)
	}
}
