package main

// Stream processor consumes Kafka events and computes real-time metrics.
// Topics: order.events, payment.events, rider.events, fulfillment.events, inventory.events
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
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/prometheus/client_golang/prometheus/promhttp"
	"github.com/redis/go-redis/v9"
	"github.com/segmentio/kafka-go"

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
		HTTPPort:     getEnv("HTTP_PORT", "8108"),
		GroupID:      getEnv("CONSUMER_GROUP_ID", "stream-processor"),
	}
}

func getEnv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
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

	// Initialise processors
	orderMetrics := processor.NewOrderMetrics()
	slaMonitor := processor.NewSLAMonitor(0.90, logger)
	orderProc := processor.NewOrderProcessor(rdb, orderMetrics, slaMonitor, logger)
	riderProc := processor.NewRiderProcessor(rdb, logger)
	paymentProc := processor.NewPaymentProcessor(rdb, logger)
	inventoryProc := processor.NewInventoryProcessor(rdb, logger)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	var wg sync.WaitGroup

	brokers := strings.Split(cfg.KafkaBrokers, ",")

	// Start Kafka consumers
	startConsumer(ctx, &wg, logger, brokers, cfg.GroupID, "order.events", func(ctx context.Context, msg kafka.Message) error {
		var event processor.OrderEvent
		if err := json.Unmarshal(msg.Value, &event); err != nil {
			return fmt.Errorf("unmarshal order event: %w", err)
		}
		return orderProc.Process(ctx, event)
	})

	startConsumer(ctx, &wg, logger, brokers, cfg.GroupID, "rider.events", func(ctx context.Context, msg kafka.Message) error {
		var event processor.RiderEvent
		if err := json.Unmarshal(msg.Value, &event); err != nil {
			return fmt.Errorf("unmarshal rider event: %w", err)
		}
		return riderProc.ProcessRiderEvent(ctx, event)
	})

	startConsumer(ctx, &wg, logger, brokers, cfg.GroupID, "rider.location.updates", func(ctx context.Context, msg kafka.Message) error {
		var update processor.LocationUpdate
		if err := json.Unmarshal(msg.Value, &update); err != nil {
			return fmt.Errorf("unmarshal location update: %w", err)
		}
		return riderProc.ProcessLocationUpdate(ctx, update)
	})

	startConsumer(ctx, &wg, logger, brokers, cfg.GroupID, "payment.events", func(ctx context.Context, msg kafka.Message) error {
		var event processor.PaymentEvent
		if err := json.Unmarshal(msg.Value, &event); err != nil {
			return fmt.Errorf("unmarshal payment event: %w", err)
		}
		return paymentProc.Process(ctx, event)
	})

	startConsumer(ctx, &wg, logger, brokers, cfg.GroupID, "inventory.events", func(ctx context.Context, msg kafka.Message) error {
		var event processor.InventoryEvent
		if err := json.Unmarshal(msg.Value, &event); err != nil {
			return fmt.Errorf("unmarshal inventory event: %w", err)
		}
		return inventoryProc.Process(ctx, event)
	})

	// HTTP server for health and metrics
	mux := http.NewServeMux()
	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"status":"ok"}`))
	})
	mux.Handle("/metrics", promhttp.Handler())

	srv := &http.Server{
		Addr:              ":" + cfg.HTTPPort,
		Handler:           mux,
		ReadHeaderTimeout: 5 * time.Second,
	}

	wg.Add(1)
	go func() {
		defer wg.Done()
		logger.Info("http server starting", "port", cfg.HTTPPort)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			logger.Error("http server error", "error", err)
		}
	}()

	// Graceful shutdown
	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)
	sig := <-sigCh
	logger.Info("shutdown signal received", "signal", sig.String())

	cancel() // cancel context — consumers will drain and exit

	shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer shutdownCancel()
	if err := srv.Shutdown(shutdownCtx); err != nil {
		logger.Error("http server shutdown error", "error", err)
	}

	wg.Wait()
	logger.Info("stream processor stopped")
}

// startConsumer launches a goroutine that reads from a Kafka topic and calls handler for each message.
func startConsumer(ctx context.Context, wg *sync.WaitGroup, logger *slog.Logger, brokers []string, groupID, topic string, handler func(context.Context, kafka.Message) error) {
	reader := kafka.NewReader(kafka.ReaderConfig{
		Brokers:        brokers,
		GroupID:        groupID,
		Topic:          topic,
		MinBytes:       1e3,  // 1KB
		MaxBytes:       10e6, // 10MB
		CommitInterval: time.Second,
		StartOffset:    kafka.LastOffset,
	})

	wg.Add(1)
	go func() {
		defer wg.Done()
		defer func() {
			if err := reader.Close(); err != nil {
				logger.Error("kafka reader close error", "topic", topic, "error", err)
			}
		}()

		logger.Info("consumer started", "topic", topic)

		for {
			msg, err := reader.FetchMessage(ctx)
			if err != nil {
				if errors.Is(err, context.Canceled) {
					logger.Info("consumer stopping", "topic", topic)
					return
				}
				logger.Error("fetch message error", "topic", topic, "error", err)
				time.Sleep(time.Second)
				continue
			}

			if err := handler(ctx, msg); err != nil {
				logger.Error("process message error", "topic", topic, "offset", msg.Offset, "error", err)
				// Non-fatal: log and continue
				continue
			}

			if err := reader.CommitMessages(ctx, msg); err != nil {
				if errors.Is(err, context.Canceled) {
					return
				}
				logger.Error("commit message error", "topic", topic, "offset", msg.Offset, "error", err)
			}
		}
	}()
}
