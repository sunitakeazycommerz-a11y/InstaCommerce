package kafka

import (
	"context"
	"testing"
	"time"

	kafkago "github.com/segmentio/kafka-go"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"go.opentelemetry.io/otel/propagation"
)

func TestValidateSchemaVersion(t *testing.T) {
	cases := []struct {
		raw     string
		want    string
		wantErr bool
	}{
		{raw: "", want: "v1"},
		{raw: "   ", want: "v1"},
		{raw: "v1", want: "v1"},
		{raw: "v12", want: "v12"},
		{raw: "1.0.0", want: "1.0.0"},
		{raw: "2.1.3", want: "2.1.3"},
		{raw: "v0", wantErr: true},
		{raw: "1.0", wantErr: true},
		{raw: "broken", wantErr: true},
	}

	for _, tc := range cases {
		got, err := ValidateSchemaVersion(tc.raw)
		if tc.wantErr {
			require.ErrorIs(t, err, ErrInvalidSchemaVersion)
			continue
		}
		require.NoError(t, err)
		assert.Equal(t, tc.want, got)
	}
}

func TestEventEnvelopeValidate(t *testing.T) {
	env := EventEnvelope{
		EventID:       "evt-1",
		EventType:     "OrderCreated",
		AggregateID:   "ord-1",
		AggregateType: "Order",
		Timestamp:     time.Now().UTC(),
		Payload:       map[string]any{"ok": true},
	}
	require.NoError(t, env.Validate())
	assert.Equal(t, "v1", env.SchemaVersion)

	env.SchemaVersion = "bad"
	require.ErrorIs(t, env.Validate(), ErrInvalidSchemaVersion)
}

func TestHeaderCarrierRoundTrip(t *testing.T) {
	headers := []kafkago.Header{{Key: "existing", Value: []byte("1")}}
	carrier := NewHeaderCarrier(&headers)

	prop := propagation.TraceContext{}
	assert.Equal(t, "1", carrier.Get("EXISTING"))
	assert.Equal(t, []string{"existing"}, carrier.Keys())

	carrier.Set("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
	carrier.Set("Traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-02")
	assert.Equal(t, "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-02", carrier.Get("traceparent"))
	assert.Len(t, headers, 2)

	ctx := prop.Extract(context.Background(), carrier)
	assert.NotNil(t, ctx)
}

func TestTruncateUTF8(t *testing.T) {
	assert.Equal(t, "hello", TruncateUTF8("hello", 10))
	assert.Equal(t, "hello", TruncateUTF8("hello", 0))
	assert.Equal(t, "hello-", TruncateUTF8("hello-🚀-world", 8))
	assert.Equal(t, "", TruncateUTF8("🚀", 2))
}
