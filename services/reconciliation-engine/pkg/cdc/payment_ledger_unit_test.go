package cdc

import (
	"context"
	"math"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestExtractInt64_RejectsNonIntegerOrOverflowFloat64(t *testing.T) {
	_, ok := extractInt64(map[string]interface{}{"amount_cents": 25.99}, "amount_cents")
	assert.False(t, ok, "fractional float64 must be rejected")

	_, ok = extractInt64(map[string]interface{}{"amount_cents": math.NaN()}, "amount_cents")
	assert.False(t, ok)

	_, ok = extractInt64(map[string]interface{}{"amount_cents": math.Inf(1)}, "amount_cents")
	assert.False(t, ok)

	_, ok = extractInt64(map[string]interface{}{"amount_cents": 9223372036854775808.0}, "amount_cents")
	assert.False(t, ok)

	val, ok := extractInt64(map[string]interface{}{"amount_cents": 2599.0}, "amount_cents")
	assert.True(t, ok)
	assert.Equal(t, int64(2599), val)
}

func TestProcessPaymentRecord_DoesNotDoubleIncrementEventsFailed(t *testing.T) {
	consumer := newTestPaymentCDCConsumer(t, nil)

	err := consumer.processPaymentRecord(context.Background(), map[string]interface{}{
		"id":           "pay-err",
		"amount_cents": int64(1500),
	}, 0, 10)
	require.Error(t, err)

	metrics := consumer.GetMetrics()
	assert.Equal(t, int64(1), metrics.eventsReceived)
	assert.Equal(t, int64(0), metrics.eventsFailed, "eventsFailed should be counted once by the consumer loop")
}
