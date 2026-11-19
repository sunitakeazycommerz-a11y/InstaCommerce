package kafka

import (
	"context"
	"testing"
	"time"

	"github.com/instacommerce/go-shared/pkg/correlation"
	kafkago "github.com/segmentio/kafka-go"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestInjectCorrelationHeaders(t *testing.T) {
	tests := []struct {
		name        string
		ctxCID      string
		inHeaders   map[string]string
		wantHeaders map[string]string
	}{
		{
			name:        "empty context and nil headers",
			ctxCID:      "",
			inHeaders:   nil,
			wantHeaders: nil,
		},
		{
			name:   "injects correlation ID without mutating input map",
			ctxCID: "cid-123",
			inHeaders: map[string]string{
				"source": "checkout",
			},
			wantHeaders: map[string]string{
				"source":           "checkout",
				correlation.Header: "cid-123",
			},
		},
		{
			name:   "preserves existing correlation_id header",
			ctxCID: "cid-new",
			inHeaders: map[string]string{
				"correlation_id": "cid-existing",
			},
			wantHeaders: map[string]string{
				"correlation_id": "cid-existing",
			},
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			ctx := context.Background()
			if tc.ctxCID != "" {
				ctx = context.WithValue(ctx, correlation.ContextKey, tc.ctxCID)
			}
			var origCopy map[string]string
			if tc.inHeaders != nil {
				origCopy = make(map[string]string, len(tc.inHeaders))
				for k, v := range tc.inHeaders {
					origCopy[k] = v
				}
			}

			got := InjectCorrelationHeaders(ctx, tc.inHeaders)
			assert.Equal(t, tc.wantHeaders, got)
			assert.Equal(t, origCopy, tc.inHeaders)
		})
	}
}

func TestContextWithMessageHeaders(t *testing.T) {
	tests := []struct {
		name      string
		presetCID string
		headers   map[string]string
		wantCID   string
	}{
		{
			name:    "extracts canonical X-Correlation-ID",
			headers: map[string]string{correlation.Header: "cid-abc"},
			wantCID: "cid-abc",
		},
		{
			name:    "extracts lowercase x-correlation-id",
			headers: map[string]string{"x-correlation-id": "cid-lower"},
			wantCID: "cid-lower",
		},
		{
			name:    "extracts snake_case correlation_id",
			headers: map[string]string{"correlation_id": "cid-snake"},
			wantCID: "cid-snake",
		},
		{
			name:      "does not overwrite preset context correlation ID",
			presetCID: "cid-preset",
			headers:   map[string]string{correlation.Header: "cid-header"},
			wantCID:   "cid-preset",
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			ctx := context.Background()
			if tc.presetCID != "" {
				ctx = context.WithValue(ctx, correlation.ContextKey, tc.presetCID)
			}
			gotCtx := ContextWithMessageHeaders(ctx, tc.headers)
			assert.Equal(t, tc.wantCID, correlation.FromContext(gotCtx))
		})
	}
}

func TestConsumerAndProducerAcceptNilLoggerAndCustomOffsets(t *testing.T) {
	c, err := NewConsumer(ConsumerConfig{
		Brokers:      []string{"localhost:9092"},
		GroupID:      "g1",
		Topics:       []string{"orders.events"},
		StartOffset:  kafkago.FirstOffset,
		ErrorBackoff: -1,
	}, func(context.Context, Message) error { return nil }, nil)
	require.NoError(t, err)
	assert.Equal(t, time.Duration(0), c.errorBackoff)
	_ = c.Close()

	p, err := NewProducer(ProducerConfig{
		Brokers:  []string{"localhost:9092"},
		ClientID: "p1",
	}, nil)
	require.NoError(t, err)
	_ = p.Close()
}
