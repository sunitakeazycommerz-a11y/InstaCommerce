package processor

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"log/slog"
	"net"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/redis/go-redis/v9"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

type recordedRedisCmd struct {
	op   string
	args []string
}

type fakeRESPServer struct {
	ln   net.Listener
	mu   sync.Mutex
	cmds []recordedRedisCmd
	done chan struct{}
}

func startFakeRESPServer(t *testing.T) *fakeRESPServer {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	require.NoError(t, err)
	s := &fakeRESPServer{ln: ln, done: make(chan struct{})}
	go func() {
		defer close(s.done)
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			go s.serveConn(conn)
		}
	}()
	t.Cleanup(func() {
		_ = ln.Close()
		<-s.done
	})
	return s
}

func (s *fakeRESPServer) addr() string {
	return s.ln.Addr().String()
}

func (s *fakeRESPServer) commands() []recordedRedisCmd {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]recordedRedisCmd, len(s.cmds))
	copy(out, s.cmds)
	return out
}

func (s *fakeRESPServer) serveConn(conn net.Conn) {
	defer conn.Close()
	r := bufio.NewReader(conn)
	inMulti := false
	var queued [][]string

	replyFor := func(args []string) string {
		if len(args) == 0 {
			return "-ERR empty\r\n"
		}
		op := strings.ToUpper(args[0])
		switch op {
		case "HELLO":
			return "*2\r\n$6\r\nserver\r\n$5\r\nredis\r\n"
		case "CLIENT", "SET":
			if op == "SET" {
				s.record(op, args[1:])
			}
			return "+OK\r\n"
		case "PING":
			return "+PONG\r\n"
		case "DEL", "INCR", "INCRBY", "EXPIRE", "HSET", "LPUSH", "GEOADD":
			s.record(op, args[1:])
			return ":1\r\n"
		default:
			return "+OK\r\n"
		}
	}

	for {
		args, err := readRESPArray(r)
		if err != nil {
			return
		}
		if len(args) == 0 {
			continue
		}
		op := strings.ToUpper(args[0])
		switch op {
		case "MULTI":
			inMulti = true
			queued = nil
			_, _ = io.WriteString(conn, "+OK\r\n")
		case "EXEC":
			inMulti = false
			var b strings.Builder
			fmt.Fprintf(&b, "*%d\r\n", len(queued))
			for _, q := range queued {
				b.WriteString(replyFor(q))
			}
			queued = nil
			_, _ = io.WriteString(conn, b.String())
		default:
			if inMulti {
				queued = append(queued, args)
				_, _ = io.WriteString(conn, "+QUEUED\r\n")
			} else {
				_, _ = io.WriteString(conn, replyFor(args))
			}
		}
	}
}

func (s *fakeRESPServer) record(op string, args []string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	copied := make([]string, len(args))
	copy(copied, args)
	s.cmds = append(s.cmds, recordedRedisCmd{op: op, args: copied})
}

func readRESPArray(r *bufio.Reader) ([]string, error) {
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
		return nil, fmt.Errorf("invalid array length %q", line)
	}
	args := make([]string, 0, n)
	for i := 0; i < n; i++ {
		hdr, err := r.ReadString('\n')
		if err != nil {
			return nil, err
		}
		hdr = strings.TrimSuffix(strings.TrimSuffix(hdr, "\n"), "\r")
		if !strings.HasPrefix(hdr, "$") {
			return nil, fmt.Errorf("expected bulk string, got %q", hdr)
		}
		sz, err := strconv.Atoi(hdr[1:])
		if err != nil || sz < 0 {
			return nil, fmt.Errorf("invalid bulk size %q", hdr)
		}
		buf := make([]byte, sz+2)
		if _, err := io.ReadFull(r, buf); err != nil {
			return nil, err
		}
		args = append(args, string(buf[:sz]))
	}
	return args, nil
}

func gatherLabelValues(t *testing.T, metricName, labelName string) map[string]struct{} {
	t.Helper()
	families, err := prometheus.DefaultGatherer.Gather()
	require.NoError(t, err)
	vals := make(map[string]struct{})
	for _, fam := range families {
		if fam.GetName() != metricName {
			continue
		}
		for _, m := range fam.GetMetric() {
			for _, lp := range m.GetLabel() {
				if lp.GetName() == labelName {
					vals[lp.GetValue()] = struct{}{}
				}
			}
		}
	}
	return vals
}

func TestHighCardinalityDomainKeysPreserveRawIDsWhilePrometheusLabelsStayBounded(t *testing.T) {
	srv := startFakeRESPServer(t)
	rdb := redis.NewClient(&redis.Options{Addr: srv.addr(), Protocol: 2})
	t.Cleanup(func() { _ = rdb.Close() })

	beforeSkuLabels := gatherLabelValues(t, "inventory_velocity_per_hour", "sku_id")
	beforeStoreLabels := gatherLabelValues(t, "inventory_stock_updates_total", "store_id")
	beforeZoneLabels := gatherLabelValues(t, "orders_total", "zone_id")

	ctx := context.Background()
	const total = 1050
	discardLogger := slog.New(slog.NewTextHandler(io.Discard, nil))

	invProc := NewInventoryProcessor(rdb, discardLogger)
	slaMon := NewSLAMonitor(0.90, discardLogger)
	defer slaMon.Close()
	ordProc := NewOrderProcessor(rdb, NewOrderMetrics(), slaMon, discardLogger)
	riderProc := NewRiderProcessor(rdb, discardLogger)
	payProc := NewPaymentProcessor(rdb, discardLogger)

	now := time.Now().UTC()
	delivered := now.Add(5 * time.Minute)

	for i := 0; i < total; i++ {
		storeID := fmt.Sprintf("store-hc-%04d", i)
		skuID := fmt.Sprintf("sku-hc-%04d", i)
		zoneID := fmt.Sprintf("zone-hc-%04d", i)
		riderID := fmt.Sprintf("rider-hc-%04d", i)
		method := fmt.Sprintf("method-hc-%04d", i)

		require.NoError(t, invProc.Process(ctx, InventoryEvent{
			EventType: "LowStockAlert",
			StoreID:   storeID,
			SKUID:     skuID,
			Quantity:  0,
			PrevQty:   5,
			Timestamp: now,
		}))

		require.NoError(t, ordProc.Process(ctx, OrderEvent{
			EventType:   "OrderDelivered",
			OrderID:     fmt.Sprintf("ord-hc-%04d", i),
			StoreID:     storeID,
			ZoneID:      zoneID,
			PlacedAt:    now,
			DeliveredAt: &delivered,
		}))

		require.NoError(t, riderProc.ProcessRiderEvent(ctx, RiderEvent{
			EventType: "RiderAssigned",
			RiderID:   riderID,
			ZoneID:    zoneID,
			Timestamp: now,
		}))
		require.NoError(t, riderProc.ProcessLocationUpdate(ctx, LocationUpdate{
			RiderID:   riderID,
			ZoneID:    zoneID,
			Latitude:  12.9716,
			Longitude: 77.5946,
			Timestamp: now,
		}))

		require.NoError(t, payProc.Process(ctx, PaymentEvent{
			EventType:   "PaymentSuccess",
			PaymentID:   fmt.Sprintf("pay-hc-%04d", i),
			AmountCents: 100,
			Method:      method,
			Timestamp:   now,
		}))
	}

	invProc.mu.RLock()
	assert.Len(t, invProc.velocity, total)
	assert.Len(t, invProc.stockouts, total)
	_, otherVelocity := invProc.velocity["other:other"]
	_, otherStockout := invProc.stockouts["other"]
	assert.False(t, otherVelocity)
	assert.False(t, otherStockout)
	invProc.mu.RUnlock()

	for i := 0; i < total; i++ {
		zoneID := fmt.Sprintf("zone-hc-%04d", i)
		pct, ok := slaMon.GetSLAPercent(zoneID)
		assert.True(t, ok, "SLA monitor must track raw zone %s", zoneID)
		assert.Equal(t, 1.0, pct)
	}
	_, otherSLA := slaMon.GetSLAPercent("other")
	assert.False(t, otherSLA)

	riderProc.mu.RLock()
	assert.Len(t, riderProc.states, total)
	assert.Equal(t, "zone-hc-1049", riderProc.states["rider-hc-1049"].ZoneID)
	riderProc.mu.RUnlock()

	payProc.mu.RLock()
	assert.Len(t, payProc.windows, total)
	_, otherPay := payProc.windows["other"]
	assert.False(t, otherPay)
	payProc.mu.RUnlock()

	recorded := srv.commands()
	seenKeys := make(map[string]struct{})
	hsetZoneIDs := make(map[string]string)
	for _, cmd := range recorded {
		if len(cmd.args) > 0 {
			seenKeys[cmd.args[0]] = struct{}{}
			assert.NotContains(t, cmd.args[0], ":other")
		}
		if cmd.op == "HSET" && len(cmd.args) >= 3 {
			for j := 1; j+1 < len(cmd.args); j += 2 {
				if cmd.args[j] == "zone_id" {
					hsetZoneIDs[cmd.args[0]] = cmd.args[j+1]
				}
			}
		}
	}
	for i := 0; i < total; i++ {
		storeID := fmt.Sprintf("store-hc-%04d", i)
		skuID := fmt.Sprintf("sku-hc-%04d", i)
		zoneID := fmt.Sprintf("zone-hc-%04d", i)
		riderID := fmt.Sprintf("rider-hc-%04d", i)
		assert.Contains(t, seenKeys, fmt.Sprintf("inventory:%s:%s", storeID, skuID))
		assert.Contains(t, seenKeys, fmt.Sprintf("stockout:%s:%s", storeID, skuID))
		assert.Contains(t, seenKeys, fmt.Sprintf("low_stock:%s:%s", storeID, skuID))
		assert.Contains(t, seenKeys, fmt.Sprintf("riders:zone:%s:active", zoneID))
		assert.Contains(t, seenKeys, fmt.Sprintf("riders:geo:%s", zoneID))
		assert.Equal(t, zoneID, hsetZoneIDs[fmt.Sprintf("rider:status:%s", riderID)])
		assert.Equal(t, zoneID, hsetZoneIDs[fmt.Sprintf("rider:location:%s", riderID)])
	}

	assert.Equal(t, maxBoundedLabelValues, len(invProc.skuGuard.seen))
	assert.Equal(t, "other", invProc.skuGuard.normalize("sku-hc-overflow", "unknown"))
	assert.Equal(t, maxBoundedLabelValues, len(invProc.storeGuard.seen))
	assert.Equal(t, "other", invProc.storeGuard.normalize("store-hc-overflow", "unknown"))

	skuLabels := gatherLabelValues(t, "inventory_velocity_per_hour", "sku_id")
	assert.LessOrEqual(t, len(skuLabels)-len(beforeSkuLabels), maxBoundedLabelValues+1)
	assert.Contains(t, skuLabels, "other")

	storeLabels := gatherLabelValues(t, "inventory_stock_updates_total", "store_id")
	assert.LessOrEqual(t, len(storeLabels)-len(beforeStoreLabels), maxBoundedLabelValues+1)
	assert.Contains(t, storeLabels, "other")

	zoneLabels := gatherLabelValues(t, "orders_total", "zone_id")
	assert.LessOrEqual(t, len(zoneLabels)-len(beforeZoneLabels), maxBoundedLabelValues+1)
	assert.Contains(t, zoneLabels, "other")

	families, err := prometheus.DefaultGatherer.Gather()
	require.NoError(t, err)
	for _, fam := range families {
		if fam.GetName() == "inventory_velocity_per_hour" {
			assert.Equal(t, "GAUGE", fam.GetType().String())
		}
	}
}
