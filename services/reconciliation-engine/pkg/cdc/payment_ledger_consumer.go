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
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/segmentio/kafka-go"
)

const (
	defaultInitialFetchBackoff = 100 * time.Millisecond
	defaultMaxFetchBackoff     = 5 * time.Second
	defaultDLTTopic            = "reconciliation.events.DLT"
	defaultMaxProcessRetries   = 3
)

type kafkaFetcher interface {
	FetchMessage(ctx context.Context) (kafka.Message, error)
	CommitMessages(ctx context.Context, msgs ...kafka.Message) error
}

type dltMessageWriter interface {
	WriteMessages(ctx context.Context, msgs ...kafka.Message) error
	Close() error
}

// CDCConsumer processes Debezium CDC events for reconciliation.
type CDCConsumer struct {
	kafkaReader       *kafka.Reader
	dltWriter         dltMessageWriter
	db                *sql.DB
	config            CDCConsumerConfig
	logger            *slog.Logger
	mu                sync.RWMutex
	dailySnapshots    map[string]*DailySnapshot
	metrics           *CDCMetrics
	done              chan struct{}
	stopOnce          sync.Once
	stopErr           error
	wg                sync.WaitGroup
	retryBackoff      time.Duration
	maxProcessRetries int
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

	if strings.TrimSpace(config.DLTTopic) == "" {
		config.DLTTopic = defaultDLTTopic
	}
	maxProcessRetries := config.MaxProcessRetries
	if maxProcessRetries <= 0 {
		maxProcessRetries = defaultMaxProcessRetries
	}
	config.MaxProcessRetries = maxProcessRetries

	var dltWriter dltMessageWriter
	if len(config.KafkaBrokers) > 0 {
		dltWriter = &kafka.Writer{
			Addr:         kafka.TCP(config.KafkaBrokers...),
			Topic:        config.DLTTopic,
			Balancer:     &kafka.LeastBytes{},
			RequiredAcks: kafka.RequireAll,
			Async:        false,
		}
	}

	reader := kafka.NewReader(readerCfg)
	return &CDCConsumer{
		kafkaReader:       reader,
		dltWriter:         dltWriter,
		db:                config.DB,
		config:            config,
		logger:            logger,
		dailySnapshots:    make(map[string]*DailySnapshot),
		metrics:           &CDCMetrics{},
		done:              make(chan struct{}),
		retryBackoff:      defaultInitialFetchBackoff,
		maxProcessRetries: maxProcessRetries,
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
		if c.dltWriter != nil {
			if err := c.dltWriter.Close(); err != nil && c.stopErr == nil {
				c.stopErr = err
			}
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
	blockedPartitions := make(map[int]bool)

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

		if err := c.processWithRetries(ctx, msg); err != nil {
			c.mu.Lock()
			c.metrics.eventsFailed++
			c.mu.Unlock()

			if dltErr := c.sendToDLT(ctx, msg, err); dltErr != nil {
				blockedPartitions[msg.Partition] = true
				if c.logger != nil {
					c.logger.Error("failed to process cdc message and DLT unavailable; holding offset",
						"error", err,
						"dlt_error", dltErr,
						"topic", msg.Topic,
						"partition", msg.Partition,
						"offset", msg.Offset,
					)
				}
				if !c.waitForRetryOrStop(ctx, backoff) {
					return
				}
				backoff = min(backoff*2, defaultMaxFetchBackoff)
				continue
			}

			c.mu.Lock()
			c.metrics.eventsDLQ++
			c.mu.Unlock()
			if c.logger != nil {
				c.logger.Warn("routed failed cdc message to DLT",
					"error", err,
					"topic", msg.Topic,
					"dlt_topic", c.dltTopic(),
					"partition", msg.Partition,
					"offset", msg.Offset,
				)
			}
			if blockedPartitions[msg.Partition] {
				continue
			}
		} else {
			c.mu.Lock()
			if msg.Offset > c.metrics.lastProcessedLSN {
				c.metrics.lastProcessedLSN = msg.Offset
			}
			c.mu.Unlock()

			if blockedPartitions[msg.Partition] {
				continue
			}
		}

		commitCtx, commitCancel := context.WithTimeout(context.Background(), 5*time.Second)
		if err := fetcher.CommitMessages(commitCtx, msg); err != nil && c.logger != nil {
			c.logger.Error("failed to commit offset", "error", err, "offset", msg.Offset)
		}
		commitCancel()
	}
}

func (c *CDCConsumer) maxRetries() int {
	if c.maxProcessRetries > 0 {
		return c.maxProcessRetries
	}
	return defaultMaxProcessRetries
}

func (c *CDCConsumer) dltTopic() string {
	if strings.TrimSpace(c.config.DLTTopic) != "" {
		return c.config.DLTTopic
	}
	return defaultDLTTopic
}

func (c *CDCConsumer) processWithRetries(ctx context.Context, msg kafka.Message) error {
	var lastErr error
	backoff := c.initialBackoff()
	attempts := c.maxRetries()
	for attempt := 0; attempt <= attempts; attempt++ {
		if c.isStopping(ctx) {
			return ctx.Err()
		}
		err := c.processMessageCtx(ctx, msg)
		if err == nil {
			return nil
		}
		lastErr = err
		if isPermanentCDCError(err) || attempt >= attempts {
			return err
		}
		if c.logger != nil {
			c.logger.Warn("transient cdc processing error; retrying",
				"attempt", attempt+1,
				"max_retries", attempts,
				"offset", msg.Offset,
				"error", err,
			)
		}
		if !c.waitForRetryOrStop(ctx, backoff) {
			return lastErr
		}
		backoff = min(backoff*2, defaultMaxFetchBackoff)
	}
	return lastErr
}

func (c *CDCConsumer) sendToDLT(ctx context.Context, msg kafka.Message, procErr error) error {
	if c.dltWriter == nil {
		return errors.New("dlt writer not configured")
	}
	headers := append([]kafka.Header(nil), msg.Headers...)
	headers = append(headers,
		kafka.Header{Key: "dlt_original_topic", Value: []byte(msg.Topic)},
		kafka.Header{Key: "dlt_original_partition", Value: []byte(strconv.Itoa(msg.Partition))},
		kafka.Header{Key: "dlt_original_offset", Value: []byte(strconv.FormatInt(msg.Offset, 10))},
		kafka.Header{Key: "dlt_error", Value: []byte(procErr.Error())},
		kafka.Header{Key: "dlt_failed_at", Value: []byte(time.Now().UTC().Format(time.RFC3339))},
	)
	dltCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	return c.dltWriter.WriteMessages(dltCtx, kafka.Message{
		Key:     msg.Key,
		Value:   msg.Value,
		Headers: headers,
		Time:    time.Now().UTC(),
	})
}

func isPermanentCDCError(err error) bool {
	if err == nil {
		return false
	}
	var syntaxErr *json.SyntaxError
	var typeErr *json.UnmarshalTypeError
	if errors.As(err, &syntaxErr) || errors.As(err, &typeErr) {
		return true
	}
	msg := strings.ToLower(err.Error())
	return strings.Contains(msg, "failed to unmarshal") || strings.Contains(msg, "missing ")
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
