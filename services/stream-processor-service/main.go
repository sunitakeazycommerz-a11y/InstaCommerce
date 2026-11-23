package main

// Stream processor consumes Kafka events and computes real-time metrics.
// Topics: order.events/orders.events, payment.events/payments.events, rider.events, fulfillment.events, inventory.events
// Outputs: Redis counters + Prometheus metrics + optional BigQuery sink

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/instacommerce/go-shared/pkg/correlation"
	"github.com/instacommerce/go-shared/pkg/health"
	"github.com/instacommerce/go-shared/pkg/observability"
	"github.com/prometheus/client_golang/prometheus/promhttp"
	"github.com/redis/go-redis/v9"
	"github.com/segmentio/kafka-go"

	"github.com/instacommerce/stream-processor-service/dedup"
	"github.com/instacommerce/stream-processor-service/processor"
)

type Config struct {
	KafkaBrokers string
	RedisAddr    string
	RedisPass    string
	HTTPPort     string
	GroupID      string
}

func loadConfig() Config {
	return Config{
		KafkaBrokers: getEnv("KAFKA_BROKERS", "localhost:9092"),
		RedisAddr:    getEnv("REDIS_ADDR", "localhost:6379"),
		RedisPass:    getEnv("REDIS_PASSWORD", ""),
		HTTPPort:     firstNonEmptyEnv("PORT", "SERVER_PORT", "HTTP_PORT", "8108"),
		GroupID:      getEnv("CONSUMER_GROUP_ID", "stream-processor"),
	}
}

func firstNonEmptyEnv(keysAndFallback ...string) string {
	if len(keysAndFallback) == 0 {
		return ""
	}
	for i := 0; i < len(keysAndFallback)-1; i++ {
		if v := strings.TrimSpace(os.Getenv(keysAndFallback[i])); v != "" {
			return v
		}
	}
	return keysAndFallback[len(keysAndFallback)-1]
}

func getEnv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func newHTTPMux(hHandler *health.Handler, readyFn func(context.Context) error) *http.ServeMux {
	if hHandler == nil {
		hHandler = health.NewHandler()
		hHandler.SetReady(true)
	}
	mux := http.NewServeMux()
	hHandler.RegisterRoutes(mux)

	readyHandler := func(w http.ResponseWriter, r *http.Request) {
		if readyFn != nil {
			checkCtx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
			defer cancel()
			if err := readyFn(checkCtx); err != nil {
				w.Header().Set("Content-Type", "application/json")
				w.WriteHeader(http.StatusServiceUnavailable)
				if _, writeErr := w.Write([]byte(`{"status":"not_ready"}`)); writeErr != nil {
					slog.Error("failed to write readiness response", "error", writeErr)
				}
				return
			}
		}
		hHandler.ReadyHandler()(w, r)
	}

	mux.HandleFunc("/health/ready", readyHandler)
	mux.HandleFunc("/ready", readyHandler)
	mux.Handle("/metrics", promhttp.Handler())
	return mux
}

func main() {
	logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo}))
	slog.SetDefault(logger)

	cfg := loadConfig()
	logger.Info("starting stream processor", "brokers", cfg.KafkaBrokers, "redis", cfg.RedisAddr)

	rdb := redis.NewClient(&redis.Options{
		Addr:     cfg.RedisAddr,
		Password: cfg.RedisPass,
		DB:       0,
	})
	defer rdb.Close()

	deduper := dedup.NewChecker(rdb, logger).WithBloomFilter(dedup.NewBloomFilter(100000, 0.01))

	orderMetrics := processor.NewOrderMetrics()
	slaMonitor := processor.NewSLAMonitor(0.90, logger)
	defer slaMonitor.Close()

	orderProc := processor.NewOrderProcessor(rdb, orderMetrics, slaMonitor, logger)
	riderProc := processor.NewRiderProcessor(rdb, logger)
	paymentProc := processor.NewPaymentProcessor(rdb, logger)
	inventoryProc := processor.NewInventoryProcessor(rdb, logger)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	otelProviders, otelErr := observability.InitOTel(ctx, observability.OTelConfig{
		ServiceName:      "stream-processor-service",
		Protocol:         observability.OTelProtocolGRPC,
		ResolveEnv:       true,
		NoopIfNoEndpoint: true,
		FallbackNoop:     true,
	}, logger)
	if otelErr != nil {
		logger.Warn("otel initialization failed; continuing without tracing", "error", otelErr)
	} else if otelProviders != nil {
		defer func() {
			shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer shutdownCancel()
			if err := otelProviders.Shutdown(shutdownCtx); err != nil {
				logger.Warn("otel shutdown failed", "error", err)
			}
		}()
	}

	var wg sync.WaitGroup

	var brokers []string
	for _, b := range strings.Split(cfg.KafkaBrokers, ",") {
		if trimmed := strings.TrimSpace(b); trimmed != "" {
			brokers = append(brokers, trimmed)
		}
	}

	var dltWriter *kafka.Writer
	if len(brokers) > 0 {
		dltWriter = &kafka.Writer{
			Addr:         kafka.TCP(brokers...),
			Balancer:     &kafka.LeastBytes{},
			RequiredAcks: kafka.RequireAll,
		}
		defer func() {
			if err := dltWriter.Close(); err != nil {
				logger.Error("dlt writer close error", "error", err)
			}
		}()
	}

	handleOrderEvent := func(ctx context.Context, msg kafka.Message) error {
		var event processor.OrderEvent
		if err := json.Unmarshal(msg.Value, &event); err != nil {
			return fmt.Errorf("unmarshal order event: %w", err)
		}
		return orderProc.Process(ctx, event)
	}

	startConsumerWithDLQ(ctx, &wg, logger, brokers, cfg.GroupID, "order.events", deduper, dltWriter, handleOrderEvent)
	startConsumerWithDLQ(ctx, &wg, logger, brokers, cfg.GroupID, "orders.events", deduper, dltWriter, handleOrderEvent)
	startConsumerWithDLQ(ctx, &wg, logger, brokers, cfg.GroupID, "fulfillment.events", deduper, dltWriter, func(ctx context.Context, msg kafka.Message) error {
		var event processor.OrderEvent
		if err := json.Unmarshal(msg.Value, &event); err != nil {
			return fmt.Errorf("unmarshal fulfillment event: %w", err)
		}
		if strings.HasPrefix(event.EventType, "Order") {
			return orderProc.Process(ctx, event)
		}
		return nil
	})

	startConsumerWithDLQ(ctx, &wg, logger, brokers, cfg.GroupID, "rider.events", deduper, dltWriter, func(ctx context.Context, msg kafka.Message) error {
		var event processor.RiderEvent
		if err := json.Unmarshal(msg.Value, &event); err != nil {
			return fmt.Errorf("unmarshal rider event: %w", err)
		}
		return riderProc.ProcessRiderEvent(ctx, event)
	})

	startConsumerWithDLQ(ctx, &wg, logger, brokers, cfg.GroupID, "rider.location.updates", deduper, dltWriter, func(ctx context.Context, msg kafka.Message) error {
		var update processor.LocationUpdate
		if err := json.Unmarshal(msg.Value, &update); err != nil {
			return fmt.Errorf("unmarshal location update: %w", err)
		}
		return riderProc.ProcessLocationUpdate(ctx, update)
	})

	handlePaymentEvent := func(ctx context.Context, msg kafka.Message) error {
		var event processor.PaymentEvent
		if err := json.Unmarshal(msg.Value, &event); err != nil {
			return fmt.Errorf("unmarshal payment event: %w", err)
		}
		return paymentProc.Process(ctx, event)
	}

	startConsumerWithDLQ(ctx, &wg, logger, brokers, cfg.GroupID, "payment.events", deduper, dltWriter, handlePaymentEvent)
	startConsumerWithDLQ(ctx, &wg, logger, brokers, cfg.GroupID, "payments.events", deduper, dltWriter, handlePaymentEvent)

	startConsumerWithDLQ(ctx, &wg, logger, brokers, cfg.GroupID, "inventory.events", deduper, dltWriter, func(ctx context.Context, msg kafka.Message) error {
		var event processor.InventoryEvent
		if err := json.Unmarshal(msg.Value, &event); err != nil {
			return fmt.Errorf("unmarshal inventory event: %w", err)
		}
		return inventoryProc.Process(ctx, event)
	})

	hHandler := health.NewHandler(health.Check{
		Name: "redis",
		Check: func(checkCtx context.Context) error {
			return rdb.Ping(checkCtx).Err()
		},
	})
	hHandler.SetReady(true)

	mux := newHTTPMux(hHandler, func(checkCtx context.Context) error {
		return rdb.Ping(checkCtx).Err()
	})

	srv := &http.Server{
		Addr:              ":" + cfg.HTTPPort,
		Handler:           correlation.Middleware(mux),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		WriteTimeout:      10 * time.Second,
		IdleTimeout:       60 * time.Second,
	}

	wg.Add(1)
	go func() {
		defer wg.Done()
		logger.Info("http server starting", "port", cfg.HTTPPort)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			logger.Error("http server error", "error", err)
		}
	}()

	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)
	sig := <-sigCh
	signal.Stop(sigCh)
	logger.Info("shutdown signal received", "signal", sig.String())

	hHandler.SetReady(false)
	cancel()

	shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer shutdownCancel()
	if err := srv.Shutdown(shutdownCtx); err != nil {
		logger.Error("http server shutdown error", "error", err)
	}

	wg.Wait()
	logger.Info("stream processor stopped")
}

// startConsumer launches a goroutine that reads from a Kafka topic and calls handler for each message.
// Duplicate messages (identified by event_id/id or topic:partition:offset) are skipped but their offsets are still committed.
type dltWriter interface {
	WriteMessages(ctx context.Context, msgs ...kafka.Message) error
}

func startConsumer(ctx context.Context, wg *sync.WaitGroup, logger *slog.Logger, brokers []string, groupID, topic string, deduper *dedup.Checker, handler func(context.Context, kafka.Message) error) {
	startConsumerWithDLQ(ctx, wg, logger, brokers, groupID, topic, deduper, nil, handler)
}

func startConsumerWithDLQ(ctx context.Context, wg *sync.WaitGroup, logger *slog.Logger, brokers []string, groupID, topic string, deduper *dedup.Checker, dlqWriter dltWriter, handler func(context.Context, kafka.Message) error) {
	reader := kafka.NewReader(kafka.ReaderConfig{
		Brokers:           brokers,
		GroupID:           groupID,
		Topic:             topic,
		MinBytes:          1e3,  // 1KB
		MaxBytes:          10e6, // 10MB
		CommitInterval:    0,
		StartOffset:       kafka.FirstOffset,
		SessionTimeout:    45 * time.Second,
		HeartbeatInterval: 3 * time.Second,
		RebalanceTimeout:  60 * time.Second,
		JoinGroupBackoff:  3 * time.Second,
		GroupBalancers: []kafka.GroupBalancer{
			kafka.RangeGroupBalancer{},
			kafka.RoundRobinGroupBalancer{},
		},
	})

	streamCfg := processor.DefaultStreamConfig()
	streamCfg.DLQHandler = newDLQHandler(dlqWriter, logger)

	dispatcher := processor.NewStreamDispatcher(
		streamCfg,
		logger,
		wrapHandlerWithDedup(deduper, logger, handler),
		func(ctx context.Context, msg kafka.Message) error {
			return reader.CommitMessages(ctx, msg)
		},
	)
	dispatcher.Start(ctx)

	wg.Add(1)
	go func() {
		defer wg.Done()
		defer func() {
			if err := reader.Close(); err != nil {
				logger.Error("kafka reader close error", "topic", topic, "error", err)
			}
		}()
		defer dispatcher.Stop()

		logger.Info("consumer started", "topic", topic)

		for {
			msg, err := reader.FetchMessage(ctx)
			if err != nil {
				if errors.Is(err, context.Canceled) {
					logger.Info("consumer stopping", "topic", topic)
					return
				}
				logger.Error("fetch message error", "topic", topic, "error", err)
				if !waitForRetry(ctx, time.Second) {
					logger.Info("consumer stopping during retry backoff", "topic", topic)
					return
				}
				continue
			}

			if err := dispatchMessage(ctx, logger, deduper, dispatcher, msg); err != nil {
				if errors.Is(err, context.Canceled) {
					return
				}
				logger.Error("dispatcher submit error", "topic", topic, "error", err)
			}
		}
	}()
}

func waitForRetry(ctx context.Context, d time.Duration) bool {
	timer := time.NewTimer(d)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return false
	case <-timer.C:
		return true
	}
}

func newDLQHandler(writer dltWriter, logger *slog.Logger) func(context.Context, kafka.Message, error) error {
	return func(ctx context.Context, msg kafka.Message, procErr error) error {
		if !isPermanentStreamError(procErr) {
			return procErr
		}
		if writer == nil {
			return errors.New("dlt writer not configured")
		}
		dltTopic := msg.Topic + ".DLT"
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
		if err := writer.WriteMessages(dltCtx, kafka.Message{
			Topic:   dltTopic,
			Key:     msg.Key,
			Value:   msg.Value,
			Headers: headers,
			Time:    time.Now().UTC(),
		}); err != nil {
			return err
		}
		if logger != nil {
			logger.Warn("routed poison message to DLT",
				"topic", msg.Topic,
				"dlt_topic", dltTopic,
				"partition", msg.Partition,
				"offset", msg.Offset,
				"error", procErr,
			)
		}
		return nil
	}
}

func isPermanentStreamError(err error) bool {
	if err == nil {
		return false
	}
	var syntaxErr *json.SyntaxError
	var typeErr *json.UnmarshalTypeError
	if errors.As(err, &syntaxErr) || errors.As(err, &typeErr) {
		return true
	}
	lower := strings.ToLower(err.Error())
	return strings.Contains(lower, "unmarshal ") || strings.Contains(lower, "panic:")
}

func wrapHandlerWithDedup(deduper *dedup.Checker, logger *slog.Logger, handler func(context.Context, kafka.Message) error) func(context.Context, kafka.Message) error {
	return func(ctx context.Context, msg kafka.Message) error {
		if err := handler(ctx, msg); err != nil {
			return err
		}
		if deduper != nil {
			if err := deduper.MarkProcessedPayload(ctx, msg.Topic, msg.Partition, msg.Offset, msg.Value); err != nil && logger != nil {
				logger.Warn("dedup mark failed after processing",
					"topic", msg.Topic, "partition", msg.Partition, "offset", msg.Offset, "error", err)
			}
		}
		return nil
	}
}

func dispatchMessage(ctx context.Context, logger *slog.Logger, deduper *dedup.Checker, dispatcher *processor.StreamDispatcher, msg kafka.Message) error {
	if deduper != nil {
		dup, err := deduper.CheckDuplicatePayload(ctx, msg.Topic, msg.Partition, msg.Offset, msg.Value)
		if err != nil {
			if logger != nil {
				logger.Warn("dedup check failed, processing anyway",
					"topic", msg.Topic, "partition", msg.Partition, "offset", msg.Offset, "error", err)
			}
		} else if dup {
			if logger != nil {
				logger.Debug("skipping duplicate message",
					"topic", msg.Topic, "partition", msg.Partition, "offset", msg.Offset)
			}
			if err := dispatcher.Skip(ctx, msg); err != nil && !errors.Is(err, context.Canceled) && logger != nil {
				logger.Error("commit duplicate offset error",
					"topic", msg.Topic, "offset", msg.Offset, "error", err)
			}
			return nil
		}
	}
	return dispatcher.Submit(ctx, msg)
}
