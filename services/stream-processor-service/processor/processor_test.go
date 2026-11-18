package processor

import (
	"bufio"
	"context"
	"encoding/json"
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
	"github.com/segmentio/kafka-go"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestOrderEventSerializationRoundTrip(t *testing.T) {
	now := time.Now().Truncate(time.Millisecond)
	deliveredAt := now.Add(8 * time.Minute)

	event := OrderEvent{
		EventType:   "OrderDelivered",
		OrderID:     "ord-999",
		UserID:      "usr-111",
		StoreID:     "store-blrm",
		TotalCents:  4590,
		ItemCount:   4,
		PlacedAt:    now,
		DeliveredAt: &deliveredAt,
		ZoneID:      "zone-koramangala",
	}

	data, err := json.Marshal(event)
	require.NoError(t, err)

	var decoded OrderEvent
	err = json.Unmarshal(data, &decoded)
	require.NoError(t, err)

	assert.Equal(t, event.EventType, decoded.EventType)
	assert.Equal(t, event.OrderID, decoded.OrderID)
	assert.Equal(t, event.UserID, decoded.UserID)
	assert.Equal(t, event.StoreID, decoded.StoreID)
	assert.Equal(t, event.TotalCents, decoded.TotalCents)
	assert.Equal(t, event.ItemCount, decoded.ItemCount)
	assert.Equal(t, event.ZoneID, decoded.ZoneID)
	assert.WithinDuration(t, event.PlacedAt, decoded.PlacedAt, time.Millisecond)
	require.NotNil(t, decoded.DeliveredAt)
	assert.WithinDuration(t, *event.DeliveredAt, *decoded.DeliveredAt, time.Millisecond)
}

func TestInventoryEventSerializationRoundTrip(t *testing.T) {
	now := time.Now().Truncate(time.Millisecond)
	event := InventoryEvent{
		EventType: "StockDepleted",
		SKUID:     "sku-avocado-01",
		StoreID:   "store-blr-04",
		Quantity:  0,
		PrevQty:   5,
		Timestamp: now,
	}

	data, err := json.Marshal(event)
	require.NoError(t, err)

	var decoded InventoryEvent
	err = json.Unmarshal(data, &decoded)
	require.NoError(t, err)

	assert.Equal(t, event.EventType, decoded.EventType)
	assert.Equal(t, event.SKUID, decoded.SKUID)
	assert.Equal(t, event.StoreID, decoded.StoreID)
	assert.Equal(t, event.Quantity, decoded.Quantity)
	assert.Equal(t, event.PrevQty, decoded.PrevQty)
	assert.WithinDuration(t, event.Timestamp, decoded.Timestamp, time.Millisecond)
}

func TestPaymentEventSerializationRoundTrip(t *testing.T) {
	now := time.Now().Truncate(time.Millisecond)
	event := PaymentEvent{
		EventType:   "PaymentFailed",
		PaymentID:   "pay-777",
		OrderID:     "ord-333",
		AmountCents: 12500,
		Method:      "upi",
		FailureCode: "INSUFFICIENT_FUNDS",
		Timestamp:   now,
	}

	data, err := json.Marshal(event)
	require.NoError(t, err)

	var decoded PaymentEvent
	err = json.Unmarshal(data, &decoded)
	require.NoError(t, err)

	assert.Equal(t, event.EventType, decoded.EventType)
	assert.Equal(t, event.PaymentID, decoded.PaymentID)
	assert.Equal(t, event.OrderID, decoded.OrderID)
	assert.Equal(t, event.AmountCents, decoded.AmountCents)
	assert.Equal(t, event.Method, decoded.Method)
	assert.Equal(t, event.FailureCode, decoded.FailureCode)
	assert.WithinDuration(t, event.Timestamp, decoded.Timestamp, time.Millisecond)
}

func TestRiderEventAndLocationSerializationRoundTrip(t *testing.T) {
	now := time.Now().Truncate(time.Millisecond)
	event := RiderEvent{
		EventType:     "RiderDelivered",
		RiderID:       "rider-88",
		ZoneID:        "zone-north",
		OrderID:       "ord-456",
		EarningsCents: 650,
		Timestamp:     now,
	}

	data, err := json.Marshal(event)
	require.NoError(t, err)

	var decoded RiderEvent
	err = json.Unmarshal(data, &decoded)
	require.NoError(t, err)

	assert.Equal(t, event.EventType, decoded.EventType)
	assert.Equal(t, event.RiderID, decoded.RiderID)
	assert.Equal(t, event.ZoneID, decoded.ZoneID)
	assert.Equal(t, event.OrderID, decoded.OrderID)
	assert.Equal(t, event.EarningsCents, decoded.EarningsCents)

	loc := LocationUpdate{
		RiderID:   "rider-88",
		Latitude:  12.9716,
		Longitude: 77.5946,
		ZoneID:    "zone-north",
		Speed:     24.5,
		Timestamp: now,
	}

	locData, err := json.Marshal(loc)
	require.NoError(t, err)

	var decodedLoc LocationUpdate
	err = json.Unmarshal(locData, &decodedLoc)
	require.NoError(t, err)

	assert.Equal(t, loc.RiderID, decodedLoc.RiderID)
	assert.Equal(t, loc.Latitude, decodedLoc.Latitude)
	assert.Equal(t, loc.Longitude, decodedLoc.Longitude)
	assert.Equal(t, loc.Speed, decodedLoc.Speed)
}

func TestSLAMonitorDeliveryThresholdAndBreachAlert(t *testing.T) {
	// Threshold at 90% (0.90)
	monitor := NewSLAMonitor(0.90, nil)

	// Zone with no deliveries returns false
	pct, ok := monitor.GetSLAPercent("zone-test")
	assert.False(t, ok)
	assert.Equal(t, 0.0, pct)

	// Record 4 fast deliveries (<= 10 min)
	for i := 0; i < 4; i++ {
		monitor.RecordDelivery("zone-test", 7.5)
	}
	pct, ok = monitor.GetSLAPercent("zone-test")
	assert.True(t, ok)
	assert.Equal(t, 1.0, pct)

	// Record 2 slow deliveries (> 10 min)
	// Total orders = 6, SLA met = 4 -> SLA % = 4/6 = 66.67% (< 90%)
	monitor.RecordDelivery("zone-test", 14.0)
	monitor.RecordDelivery("zone-test", 15.0)

	pct, ok = monitor.GetSLAPercent("zone-test")
	assert.True(t, ok)
	assert.InDelta(t, 4.0/6.0, pct, 0.001)

	// Ensure AlertCh received the alert
	assert.NotNil(t, monitor.AlertCh())
}

func TestSLAMonitorSlidingWindowEviction(t *testing.T) {
	monitor := NewSLAMonitor(0.90, nil)

	// Pre-populate window with an old record
	oldTime := time.Now().Add(-35 * time.Minute)
	w := &SLAWindow{
		ZoneID:      "zone-old",
		WindowStart: oldTime,
		records: []deliveryRecord{
			{timestamp: oldTime, deliveryMins: 8.0},
		},
	}

	monitor.evictOldRecords(w, time.Now())
	assert.Empty(t, w.records, "record outside 30 min window should be evicted")
}

func TestInventoryProcessorInternalVelocityAndCascadeTracking(t *testing.T) {
	proc := NewInventoryProcessor(nil, nil)

	// Test velocity tracking
	now := time.Now()
	proc.updateVelocity("store-1", "sku-milk", -5, now)
	proc.updateVelocity("store-1", "sku-milk", -3, now)

	proc.mu.RLock()
	records := proc.velocity["store-1:sku-milk"]
	assert.Len(t, records, 2)
	assert.Equal(t, -5, records[0].delta)
	assert.Equal(t, -3, records[1].delta)
	proc.mu.RUnlock()

	// Test stockout cascade detection (> 10 SKUs at zero in single store)
	for i := 1; i <= 12; i++ {
		proc.trackStockout("store-1", fmt.Sprintf("sku-%d", i), true)
	}

	proc.mu.RLock()
	assert.Equal(t, 12, len(proc.stockouts["store-1"]))
	proc.mu.RUnlock()

	// Replenish one SKU
	proc.trackStockout("store-1", "sku-1", false)
	proc.mu.RLock()
	assert.Equal(t, 11, len(proc.stockouts["store-1"]))
	proc.mu.RUnlock()
}

func TestPaymentProcessorInternalSuccessRateCalculation(t *testing.T) {
	proc := NewPaymentProcessor(nil, nil)

	// Record 3 successes and 1 failure for UPI
	proc.recordWindowEntry("upi", true)
	proc.recordWindowEntry("upi", true)
	proc.recordWindowEntry("upi", true)
	proc.recordWindowEntry("upi", false)

	proc.mu.RLock()
	entries := proc.windows["upi"]
	assert.Len(t, entries, 4)
	proc.mu.RUnlock()

	// Verify concurrent additions
	var wg sync.WaitGroup
	for i := 0; i < 20; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			proc.recordWindowEntry("card", idx%2 == 0)
		}(i)
	}
	wg.Wait()

	proc.mu.RLock()
	assert.Len(t, proc.windows["card"], 20)
	proc.mu.RUnlock()
}

func TestRiderProcessorInternalStateTransitions(t *testing.T) {
	proc := NewRiderProcessor(nil, nil)

	now := time.Now()
	// Simulate state transitions
	proc.mu.Lock()
	proc.states["rider-10"] = &riderState{
		Status:    "active",
		ZoneID:    "zone-south",
		UpdatedAt: now,
	}
	proc.mu.Unlock()

	proc.mu.RLock()
	st, exists := proc.states["rider-10"]
	assert.True(t, exists)
	assert.Equal(t, "active", st.Status)
	assert.Equal(t, "zone-south", st.ZoneID)
	proc.mu.RUnlock()
}

func TestEnvelopeUnwrappingAndFieldCasingAcrossProcessors(t *testing.T) {
	// 1. OrderEvent nested envelope with OrderCreated / OrderPacked / OrderDispatched
	orderEnv := []byte(`{
		"id": "evt-ord-1",
		"event_type": "OrderCreated",
		"aggregate_id": "ord-env-100",
		"timestamp": "2026-03-01T10:00:00Z",
		"payload": {
			"user_id": "usr-55",
			"store_id": "store-koramangala",
			"zone_id": "zone-1",
			"total_cents": 7800,
			"items": [{"sku": "a"}, {"sku": "b"}]
		}
	}`)
	var ordEvt OrderEvent
	require.NoError(t, json.Unmarshal(orderEnv, &ordEvt))
	assert.Equal(t, "OrderCreated", ordEvt.EventType)
	assert.Equal(t, "ord-env-100", ordEvt.OrderID)
	assert.Equal(t, "store-koramangala", ordEvt.StoreID)
	assert.Equal(t, int64(7800), ordEvt.TotalCents)
	assert.Equal(t, 2, ordEvt.ItemCount)

	// 2. PaymentEvent nested envelope with PaymentAuthorized / PaymentCaptured / PaymentRefunded
	payEnv := []byte(`{
		"eventId": "evt-pay-1",
		"eventType": "PaymentCaptured",
		"aggregateId": "pay-env-200",
		"payload": {
			"order_id": "ord-env-100",
			"amount_cents": 7800,
			"payment_method": "upi"
		}
	}`)
	var payEvt PaymentEvent
	require.NoError(t, json.Unmarshal(payEnv, &payEvt))
	assert.Equal(t, "PaymentCaptured", payEvt.EventType)
	assert.Equal(t, "pay-env-200", payEvt.PaymentID)
	assert.Equal(t, int64(7800), payEvt.AmountCents)
	assert.Equal(t, "upi", payEvt.Method)

	// 3. InventoryEvent nested envelope with StockReserved / LowStockAlert
	invEnv := []byte(`{
		"event_type": "LowStockAlert",
		"payload": {
			"product_id": "sku-bread-1",
			"warehouse_id": "wh-1",
			"current_quantity": 0,
			"threshold": 5
		}
	}`)
	var invEvt InventoryEvent
	require.NoError(t, json.Unmarshal(invEnv, &invEvt))
	assert.Equal(t, "LowStockAlert", invEvt.EventType)
	assert.Equal(t, "sku-bread-1", invEvt.SKUID)
	assert.Equal(t, "wh-1", invEvt.StoreID)
	assert.Equal(t, 0, invEvt.Quantity)
	assert.Equal(t, 5, invEvt.Threshold)

	// 4. LocationUpdate with snake_case lat/lng/rider_id/timestamp_ms
	locSnake := []byte(`{
		"rider_id": "rider-snake-1",
		"lat": 12.9352,
		"lng": 77.6245,
		"zone_id": "zone-hsr",
		"speed_kmh": 18.5,
		"timestamp_ms": 1710000000000
	}`)
	var loc LocationUpdate
	require.NoError(t, json.Unmarshal(locSnake, &loc))
	assert.Equal(t, "rider-snake-1", loc.RiderID)
	assert.InDelta(t, 12.9352, loc.Latitude, 0.0001)
	assert.InDelta(t, 77.6245, loc.Longitude, 0.0001)
	assert.Equal(t, "zone-hsr", loc.ZoneID)
	assert.False(t, loc.Timestamp.IsZero())

	// 5. SLAMonitor Close idempotency via sync.Once
	mon := NewSLAMonitor(0.90, nil)
	mon.Close()
	mon.Close()
}

func TestExtractMessageEventID_DelegatesToSharedNestedPayloadExtractor(t *testing.T) {
	assert.Equal(t, "evt-nested-1", ExtractMessageEventID(kafka.Message{
		Value: []byte(`{"type":"OrderCreated","payload":{"eventId":"evt-nested-1"}}`),
	}))
	assert.Equal(t, "evt-top-1", ExtractMessageEventID(kafka.Message{
		Value: []byte(`{"id":"evt-top-1"}`),
	}))
}

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

	// Verify in-memory domain maps kept all 1,050 raw keys without collapsing onto "other".
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

	// Verify Redis keys received raw IDs (all 1,050 distinct keys, zero "other" corruption).
	recorded := srv.commands()
	seenKeys := make(map[string]struct{})
	hsetZoneIDs := make(map[string]string)
	for _, cmd := range recorded {
		if len(cmd.args) > 0 {
			seenKeys[cmd.args[0]] = struct{}{}
			assert.NotContains(t, cmd.args[0], ":other", "Redis key %q must not collapse onto 'other'", cmd.args[0])
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

	// Verify Prometheus label guards bounded dynamic dimensions to maxBoundedLabelValues + "other".
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

	// Verify Prometheus metric type inspection passes cleanly without extra go.mod imports.
	families, err := prometheus.DefaultGatherer.Gather()
	require.NoError(t, err)
	for _, fam := range families {
		if fam.GetName() == "inventory_velocity_per_hour" {
			assert.Equal(t, "GAUGE", fam.GetType().String())
		}
	}
}
