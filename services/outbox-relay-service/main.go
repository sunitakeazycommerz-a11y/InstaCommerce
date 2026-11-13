package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"sync"
	"syscall"
	"time"

	"github.com/instacommerce/go-shared/pkg/correlation"
	"github.com/instacommerce/go-shared/pkg/health"
	"github.com/jackc/pgx/v5/pgxpool"
)

func main() {
	cfg, err := loadConfig()
	if err != nil {
		slog.Error("failed to load config", "error", err)
		os.Exit(1)
	}

	logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: cfg.LogLevel}))
	slog.SetDefault(logger)

	ctx := context.Background()
	telemetry, err := setupTelemetry(ctx, cfg, logger)
	if err != nil {
		logger.Error("failed to set up telemetry", "error", err)
		os.Exit(1)
	}

	dbPool, err := pgxpool.New(ctx, cfg.DatabaseURL)
	if err != nil {
		logger.Error("failed to connect to postgres", "error", err)
		os.Exit(1)
	}
	defer dbPool.Close()

	if err := dbPool.Ping(ctx); err != nil {
		logger.Error("postgres ping failed", "error", err)
		os.Exit(1)
	}

	producer, kafkaConfig, err := newKafkaProducer(cfg)
	if err != nil {
		logger.Error("failed to create kafka producer", "error", err)
		os.Exit(1)
	}
	defer func() {
		if err := producer.Close(); err != nil {
			logger.Error("failed to close kafka producer", "error", err)
		}
	}()

	metrics, err := newRelayMetrics(telemetry.meter)
	if err != nil {
		logger.Error("failed to initialize metrics", "error", err)
		os.Exit(1)
	}

	service, err := newRelayService(cfg, logger, dbPool, producer, kafkaConfig, telemetry.tracer, metrics)
	if err != nil {
		logger.Error("failed to initialize relay service", "error", err)
		os.Exit(1)
	}

	healthHandler := health.NewHandler(
		health.Check{
			Name: "database",
			Check: func(ctx context.Context) error {
				return service.db.Ping(ctx)
			},
		},
		health.Check{
			Name: "kafka",
			Check: func(ctx context.Context) error {
				return service.checkKafka(ctx)
			},
		},
	)
	healthHandler.SetReady(true)

	mux := http.NewServeMux()
	healthHandler.RegisterRoutes(mux)
	mux.HandleFunc("/ready", healthHandler.ReadyHandler())

	server := &http.Server{
		Addr:              ":" + cfg.Port,
		Handler:           correlation.Middleware(mux),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      15 * time.Second,
		IdleTimeout:       60 * time.Second,
	}

	errCh := make(chan error, 1)
	go func() {
		logger.Info("http server listening", "addr", server.Addr)
		if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			errCh <- err
		}
		close(errCh)
	}()

	pollCtx, pollCancel := context.WithCancel(ctx)
	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		service.pollLoop(pollCtx)
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)

	select {
	case sig := <-quit:
		logger.Info("received shutdown signal", "signal", sig.String())
	case err := <-errCh:
		if err != nil {
			logger.Error("http server error", "error", err)
		}
	}
	signal.Stop(quit)

	service.shuttingDown.Store(true)
	pollCancel()
	health.ShutdownServer(server, healthHandler, cfg.ShutdownTimeout, logger)

	shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), cfg.ShutdownTimeout)
	defer shutdownCancel()

	drainDone := make(chan struct{})
	go func() {
		wg.Wait()
		close(drainDone)
	}()

	select {
	case <-drainDone:
		logger.Info("relay worker drained")
	case <-shutdownCtx.Done():
		logger.Warn("relay worker drain timed out")
	}

	if err := telemetry.shutdown(shutdownCtx); err != nil {
		logger.Error("telemetry shutdown failed", "error", err)
	}

	logger.Info("shutdown complete")
}
