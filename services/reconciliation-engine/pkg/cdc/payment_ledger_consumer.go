package cdc

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"strings"
	"sync"
	"time"

	"github.com/segmentio/kafka-go"
)

const (
	defaultInitialFetchBackoff = 100 * time.Millisecond
	defaultMaxFetchBackoff     = 5 * time.Second
)

type kafkaFetcher interface {
	FetchMessage(ctx context.Context) (kafka.Message, error)
	CommitMessages(ctx context.Context, msgs ...kafka.Message) error
}

// CDCConsumer processes Debezium CDC events for reconciliation.
type CDCConsumer struct {
	kafkaReader    *kafka.Reader
	db             *sql.DB
	config         CDCConsumerConfig
	logger         *slog.Logger
	mu             sync.RWMutex
	dailySnapshots map[string]*DailySnapshot
	metrics        *CDCMetrics
	done           chan struct{}
	stopOnce       sync.Once
	stopErr        error
	wg             sync.WaitGroup
	retryBackoff   time.Duration
}

func resolveTopics(config CDCConsumerConfig) []string {
	seen := make(map[string]struct{})
	var topics []string
	addTopic := func(raw string) {
		trimmed := strings.TrimSpace(raw)
		if trimmed == "" {
			return
		}
		if _, exists := seen[trimmed]; exists {
			return
		}
		seen[trimmed] = struct{}{}
		topics = append(topics, trimmed)
	}
	for _, t := range config.CDCTopics {
		addTopic(t)
	}
	if len(topics) == 0 {
		addTopic(config.CDCTopic)
	}
	if len(topics) == 0 {
		addTopic("payments.cdc")
		addTopic("reconciliation.cdc")
	}
	return topics
}

// NewCDCConsumer creates a new CDC consumer instance.
func NewCDCConsumer(_ context.Context, config CDCConsumerConfig, logger *slog.Logger) (*CDCConsumer, error) {
	if logger == nil {
		logger = slog.Default()
	}
	if config.KafkaGroupID == "" {
		config.KafkaGroupID = "reconciliation-cdc-consumer"
	}
	topics := resolveTopics(config)
	config.CDCTopics = topics
	if config.CDCTopic == "" {
		config.CDCTopic = topics[0]
	}
	if config.MinBytes == 0 {
		config.MinBytes = 10 * 1024
	}
	if config.MaxBytes == 0 {
		config.MaxBytes = 10 * 1024 * 1024
	}
	if config.MaxWait == 0 {
		config.MaxWait = 5 * time.Second
	}
	if config.CommitInterval == 0 {
		config.CommitInterval = 10 * time.Second
	}
	if config.BatchSize == 0 {
		config.BatchSize = 500
	}
	if config.BatchTimeout == 0 {
		config.BatchTimeout = 5 * time.Second
	}

	readerCfg := kafka.ReaderConfig{
		Brokers:        config.KafkaBrokers,
		GroupID:        config.KafkaGroupID,
		MinBytes:       config.MinBytes,
		MaxBytes:       config.MaxBytes,
		MaxWait:        config.MaxWait,
		CommitInterval: config.CommitInterval,
		StartOffset:    kafka.LastOffset,
	}
	if len(topics) == 1 {
		readerCfg.Topic = topics[0]
	} else {
		readerCfg.GroupTopics = topics
	}

	reader := kafka.NewReader(readerCfg)
	return &CDCConsumer{
		kafkaReader:    reader,
		db:             config.DB,
		config:         config,
		logger:         logger,
		dailySnapshots: make(map[string]*DailySnapshot),
		metrics:        &CDCMetrics{},
		done:           make(chan struct{}),
		retryBackoff:   defaultInitialFetchBackoff,
	}, nil
}

func (c *CDCConsumer) Start(ctx context.Context) error {
	if c == nil {
		return nil
	}
	c.mu.Lock()
	if c.done == nil {
		c.done = make(chan struct{})
	}
	c.mu.Unlock()
	c.wg.Add(1)
	go func() {
		defer c.wg.Done()
		c.runConsumerLoop(ctx)
	}()
	return nil
}

func (c *CDCConsumer) Stop() error {
	if c == nil {
		return nil
	}
	c.stopOnce.Do(func() {
		c.mu.Lock()
		if c.done == nil {
			c.done = make(chan struct{})
		}
		done := c.done
		c.mu.Unlock()
		close(done)
		if c.kafkaReader != nil {
			c.stopErr = c.kafkaReader.Close()
		}
	})
	c.wg.Wait()
	return c.stopErr
}

func (c *CDCConsumer) runConsumerLoop(ctx context.Context) {
	var fetcher kafkaFetcher
	if c.kafkaReader != nil {
		fetcher = c.kafkaReader
	}
	c.runConsumerLoopWithFetcher(ctx, fetcher)
}

func (c *CDCConsumer) isStopping(ctx context.Context) bool {
	if ctx.Err() != nil {
		return true
	}
	if c.done == nil {
		return false
	}
	select {
	case <-c.done:
		return true
	default:
		return false
	}
}

func (c *CDCConsumer) waitForRetryOrStop(ctx context.Context, delay time.Duration) bool {
	timer := time.NewTimer(delay)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return false
	case <-c.done:
		return false
	case <-timer.C:
		return true
	}
}

func (c *CDCConsumer) initialBackoff() time.Duration {
	if c.retryBackoff > 0 {
		return c.retryBackoff
	}
	return defaultInitialFetchBackoff
}

func (c *CDCConsumer) runConsumerLoopWithFetcher(ctx context.Context, fetcher kafkaFetcher) {
	maxWait := c.config.MaxWait
	if maxWait <= 0 {
		maxWait = 5 * time.Second
	}
	initialBackoff := c.initialBackoff()
	backoff := initialBackoff

	for {
		if c.isStopping(ctx) {
			if c.logger != nil {
				c.logger.Info("cdc consumer stopping")
			}
			return
		}
		if fetcher == nil {
			select {
			case <-ctx.Done():
			case <-c.done:
			}
			return
		}

		readCtx, cancel := context.WithTimeout(ctx, maxWait*2)
		msg, err := fetcher.FetchMessage(readCtx)
		cancel()

		if err != nil {
			if errors.Is(err, io.EOF) || errors.Is(err, context.Canceled) || c.isStopping(ctx) {
				return
			}
			if errors.Is(err, context.DeadlineExceeded) {
				backoff = initialBackoff
				continue
			}
			if c.logger != nil {
				c.logger.Error("failed to fetch cdc message", "error", err, "backoff", backoff)
			}
			c.mu.Lock()
			c.metrics.eventsFailed++
			c.mu.Unlock()
			if !c.waitForRetryOrStop(ctx, backoff) {
				return
			}
			backoff = min(backoff*2, defaultMaxFetchBackoff)
			continue
		}
		backoff = initialBackoff

		if err := c.processMessageCtx(ctx, msg); err != nil {
			if c.logger != nil {
				c.logger.Warn("failed to process cdc message", "error", err, "offset", msg.Offset)
			}
			c.mu.Lock()
			c.metrics.eventsFailed++
			c.mu.Unlock()
			continue
		}

		c.mu.Lock()
		if msg.Offset > c.metrics.lastProcessedLSN {
			c.metrics.lastProcessedLSN = msg.Offset
		}
		c.mu.Unlock()

		commitCtx, commitCancel := context.WithTimeout(context.Background(), 5*time.Second)
		if err := fetcher.CommitMessages(commitCtx, msg); err != nil && c.logger != nil {
			c.logger.Error("failed to commit offset", "error", err, "offset", msg.Offset)
		}
		commitCancel()
	}
}

func (c *CDCConsumer) processMessage(msg kafka.Message) error {
	return c.processMessageCtx(context.Background(), msg)
}

func (c *CDCConsumer) processMessageCtx(ctx context.Context, msg kafka.Message) error {
	trimmed := bytes.TrimSpace(msg.Value)
	if len(trimmed) == 0 || bytes.Equal(trimmed, []byte("null")) {
		return nil
	}

	var event DebeziumChangeEvent
	if err := json.Unmarshal(trimmed, &event); err != nil {
		return fmt.Errorf("failed to unmarshal debezium event: %w", err)
	}
	if event.Envelope.Op == "" && event.Envelope.After == nil && event.Envelope.Source.Table == "" {
		if err := json.Unmarshal(trimmed, &event.Envelope); err != nil {
			return fmt.Errorf("failed to unmarshal unwrapped debezium event: %w", err)
		}
	}

	op := strings.ToLower(strings.TrimSpace(event.Envelope.Op))
	after := event.Envelope.After
	switch op {
	case "c", "i", "u", "r":
	default:
		return nil
	}

	tsMs := event.Envelope.Source.TsMs
	if tsMs == 0 {
		tsMs = event.Envelope.TsMs
	}
	lsn := event.Envelope.Source.LSN
	if lsn == 0 {
		lsn = event.Envelope.LSN
	}

	table := strings.ToLower(strings.TrimSpace(event.Envelope.Source.Table))
	if table == "" && strings.Contains(strings.ToLower(msg.Topic), "payment") {
		table = "payments"
	}

	switch table {
	case "reconciliation_runs":
		return c.processRunRecord(after, tsMs)
	case "reconciliation_mismatches":
		return c.processMismatchRecord(after, tsMs)
	case "payments":
		return c.processPaymentRecord(ctx, after, tsMs, lsn)
	default:
		if c.logger != nil {
			c.logger.Debug("ignoring event from unknown table", "table", table)
		}
		return nil
	}
}
