package main

import (
	"context"
	"fmt"
	"log/slog"
	"os"
	"os/signal"
	"sync"
	"syscall"
	"time"

	"cloud.google.com/go/bigquery"
	"github.com/instacommerce/go-shared/pkg/health"
	"github.com/segmentio/kafka-go"
)

const serviceName = "cdc-consumer-service"

func main() {
	cfg, err := loadConfig()
	if err != nil {
		slog.Error("configuration error", "error", err)
		os.Exit(1)
	}

	logger := setupLogger(cfg.LogLevel)
	metrics := initMetrics()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	tracerProvider, tracer := setupTracing(ctx, logger)
	if tracerProvider != nil {
		defer func() {
			shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 10*time.Second)
			defer shutdownCancel()
			if err := tracerProvider.Shutdown(shutdownCtx); err != nil {
				logger.Warn("tracing shutdown failed", "error", err)
			}
		}()
	}

	bqClient, err := bigquery.NewClient(ctx, cfg.BQProject)
	if err != nil {
		logger.Error("failed to create bigquery client", "error", err)
		os.Exit(1)
	}
	defer bqClient.Close()

	inserter := bqClient.Dataset(cfg.BQDataset).Table(cfg.BQTable).Inserter()
	inserter.IgnoreUnknownValues = true
	inserter.SkipInvalidRows = true

	dlqWriter := &kafka.Writer{
		Addr:         kafka.TCP(cfg.KafkaBrokers...),
		Topic:        cfg.KafkaDLQTopic,
		Balancer:     &kafka.LeastBytes{},
		RequiredAcks: kafka.RequireAll,
		Async:        false,
	}
	defer dlqWriter.Close()

	startOffset := kafka.FirstOffset
	if cfg.KafkaAutoOffsetReset == "latest" {
		startOffset = kafka.LastOffset
	}

	readers := make([]*kafka.Reader, 0, len(cfg.KafkaTopics))
	for _, topic := range cfg.KafkaTopics {
		reader := kafka.NewReader(kafka.ReaderConfig{
			Brokers:        cfg.KafkaBrokers,
			GroupID:        cfg.KafkaGroupID,
			Topic:          topic,
			MinBytes:       cfg.KafkaMinBytes,
			MaxBytes:       cfg.KafkaMaxBytes,
			MaxWait:        cfg.KafkaMaxWait,
			CommitInterval: 0,
			StartOffset:    startOffset,
		})
		readers = append(readers, reader)
		defer reader.Close()
	}

	schemaHandler := NewSchemaEvolutionHandler(bqClient.Dataset(cfg.BQDataset).Table(cfg.BQTable), logger)
	healthHandler := health.NewHandler()
	server := startHTTPServer(cfg.Port, healthHandler, logger)

	errCh := make(chan error, 1)
	reportErr := func(err error) {
		select {
		case errCh <- err:
		default:
		}
	}

	batchCh := make(chan batchItem, cfg.BatchSize*2)
	var consumerWg sync.WaitGroup
	for _, reader := range readers {
		consumerWg.Add(1)
		go func(r *kafka.Reader) {
			defer consumerWg.Done()
			runConsumer(ctx, r, batchCh, dlqWriter, cfg, metrics, logger, tracer, schemaHandler, reportErr)
		}(reader)
	}

	var batchWg sync.WaitGroup
	batchWg.Add(1)
	go func() {
		defer batchWg.Done()
		if err := runBatcher(batchCh, inserter, dlqWriter, cfg, metrics, logger, tracer); err != nil {
			reportErr(err)
		}
	}()

	healthHandler.SetReady(true)
	logger.Info("cdc consumer service started",
		"port", cfg.Port,
		"group_id", cfg.KafkaGroupID,
		"topics", cfg.KafkaTopics,
		"dlq_topic", cfg.KafkaDLQTopic,
		"bq_table", fmt.Sprintf("%s.%s.%s", cfg.BQProject, cfg.BQDataset, cfg.BQTable),
	)

	signalCh := make(chan os.Signal, 1)
	signal.Notify(signalCh, syscall.SIGINT, syscall.SIGTERM)

	select {
	case sig := <-signalCh:
		logger.Info("received shutdown signal", "signal", sig)
	case err := <-errCh:
		if err != nil {
			logger.Error("service error", "error", err)
		}
	}

	healthHandler.SetReady(false)
	cancel()
	signal.Stop(signalCh)
	close(signalCh)

	consumerWg.Wait()
	close(batchCh)
	batchWg.Wait()

	shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer shutdownCancel()
	if err := server.Shutdown(shutdownCtx); err != nil {
		logger.Warn("http server shutdown failed", "error", err)
	}
	logger.Info("cdc consumer service stopped")
}
