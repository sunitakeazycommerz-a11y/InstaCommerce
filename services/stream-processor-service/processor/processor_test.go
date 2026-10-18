package processor

import (
	"encoding/json"
	"fmt"
	"sync"
	"testing"
	"time"

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
