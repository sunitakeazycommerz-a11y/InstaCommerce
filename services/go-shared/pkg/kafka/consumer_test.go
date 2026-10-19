package kafka

import (
	"context"
	"log/slog"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestNewConsumerConfigValidationAndDefaults(t *testing.T) {
	logger := slog.Default()

	// Missing brokers
	_, err := NewConsumer(ConsumerConfig{
		GroupID: "test-group",
		Topics:  []string{"test.topic"},
	}, func(ctx context.Context, msg Message) error { return nil }, logger)
	require.Error(t, err)
	assert.Contains(t, err.Error(), "at least one broker address is required")

	// Missing groupID
	_, err = NewConsumer(ConsumerConfig{
		Brokers: []string{"localhost:9092"},
		Topics:  []string{"test.topic"},
	}, func(ctx context.Context, msg Message) error { return nil }, logger)
	require.Error(t, err)
	assert.Contains(t, err.Error(), "GroupID is required")

	// Missing topics
	_, err = NewConsumer(ConsumerConfig{
		Brokers: []string{"localhost:9092"},
		GroupID: "test-group",
	}, func(ctx context.Context, msg Message) error { return nil }, logger)
	require.Error(t, err)
	assert.Contains(t, err.Error(), "at least one topic is required")

	// Missing handler
	_, err = NewConsumer(ConsumerConfig{
		Brokers: []string{"localhost:9092"},
		GroupID: "test-group",
		Topics:  []string{"test.topic"},
	}, nil, logger)
	require.Error(t, err)
	assert.Contains(t, err.Error(), "handler must not be nil")

	// Valid creation with defaults
	consumer, err := NewConsumer(ConsumerConfig{
		Brokers: []string{"localhost:9092"},
		GroupID: "test-group",
		Topics:  []string{"test.topic"},
	}, func(ctx context.Context, msg Message) error { return nil }, logger)
	require.NoError(t, err)
	require.NotNil(t, consumer)
	assert.NotNil(t, consumer.reader)
	_ = consumer.Close()
}

func TestConsumerConfigCustomTimeouts(t *testing.T) {
	logger := slog.Default()

	cfg := ConsumerConfig{
		Brokers:           []string{"localhost:9092"},
		GroupID:           "custom-group",
		Topics:            []string{"custom.topic"},
		SessionTimeout:    60 * time.Second,
		HeartbeatInterval: 5 * time.Second,
		RebalanceTimeout:  120 * time.Second,
		JoinGroupBackoff:  5 * time.Second,
	}

	consumer, err := NewConsumer(cfg, func(ctx context.Context, msg Message) error { return nil }, logger)
	require.NoError(t, err)
	require.NotNil(t, consumer)
	_ = consumer.Close()
}
