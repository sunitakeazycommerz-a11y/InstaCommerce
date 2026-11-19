package main

import (
	"context"
	"errors"
	"net/http"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/instacommerce/go-shared/pkg/correlation"
	"github.com/instacommerce/go-shared/pkg/health"
	_ "github.com/lib/pq"
	"github.com/prometheus/client_golang/prometheus/promhttp"
	"go.opentelemetry.io/otel"

	"github.com/instacommerce/reconciliation-engine/api"
	"github.com/instacommerce/reconciliation-engine/pkg/cdc"
	"github.com/instacommerce/reconciliation-engine/pkg/reconciliation"
)

func main() {
	cfg := loadConfig()
	logger := newLogger(cfg.LogLevel).With("service", cfg.ServiceName)

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	shutdownTracer, err := initTracer(ctx, cfg.ServiceName, logger)
	if err != nil {
		logger.Error("failed to initialize tracing", "error", err)
	}

	db, err := initDatabase(ctx, cfg, logger)
	if err != nil {
		logger.Error("failed to initialize database", "error", err)
		return
	}
	defer db.Close()

	if err := db.PingContext(ctx); err != nil {
		logger.Error("failed to ping database", "error", err)
		return
	}
	logger.Info("database connection established")

	eventPub := reconciliation.NewEventPublisher(cfg.KafkaBrokers, cfg.KafkaTopic, logger)
	defer eventPub.Close()

	dbReconciler := reconciliation.NewDBReconciler(db, logger, otel.Tracer(cfg.ServiceName), eventPub)
	dailyScheduler := reconciliation.NewDailyScheduler(dbReconciler, logger, cfg.Schedule)

	var cdcConsumer *cdc.CDCConsumer
	if cfg.CDCEnabled && len(cfg.KafkaBrokers) > 0 {
		cdcConsumer, err = cdc.NewCDCConsumer(ctx, cdc.CDCConsumerConfig{
			KafkaBrokers:   cfg.KafkaBrokers,
			KafkaGroupID:   cfg.CDCGroupID,
			CDCTopics:      cfg.CDCTopics,
			DB:             db,
			MinBytes:       cfg.CDCMinBytes,
			MaxBytes:       cfg.CDCMaxBytes,
			MaxWait:        cfg.CDCMaxWait,
			CommitInterval: cfg.CDCCommitInterval,
			BatchSize:      cfg.CDCBatchSize,
			BatchTimeout:   cfg.CDCBatchTimeout,
		}, logger)
		if err != nil {
			logger.Error("failed to create cdc consumer", "error", err)
			cdcConsumer = nil
		}
	}

	reconciliationHandler := api.NewReconciliationHandler(dbReconciler, logger)
	healthHandler := health.NewHandler()
	mux := http.NewServeMux()
	healthHandler.RegisterRoutes(mux)
	mux.HandleFunc("/ready", healthHandler.ReadyHandler())
	mux.Handle("/metrics", promhttp.Handler())
	mux.HandleFunc("/reconciliation/runs", reconciliationHandler.HandleListRuns)
	mux.HandleFunc("/reconciliation/pending", reconciliationHandler.HandleGetPendingMismatches)
	mux.HandleFunc("/pending", reconciliationHandler.HandleGetPendingMismatches)
	mux.HandleFunc("/reconciliation/runs/", func(w http.ResponseWriter, r *http.Request) {
		if strings.Contains(r.URL.Path, "/mismatches") {
			reconciliationHandler.HandleGetRunMismatches(w, r)
		} else {
			http.NotFound(w, r)
		}
	})
	mux.HandleFunc("/reconciliation/mismatches/", func(w http.ResponseWriter, r *http.Request) {
		switch {
		case strings.Contains(r.URL.Path, "/review"):
			reconciliationHandler.HandleReviewMismatch(w, r)
		case strings.Contains(r.URL.Path, "/fix"):
			reconciliationHandler.HandleApplyFix(w, r)
		default:
			http.NotFound(w, r)
		}
	})

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
	}()

	if err := dailyScheduler.Start(ctx); err != nil {
		logger.Error("failed to start scheduler", "error", err)
		return
	}
	if cdcConsumer != nil {
		if err := cdcConsumer.Start(ctx); err != nil {
			logger.Error("failed to start cdc consumer", "error", err)
		}
	}

	healthHandler.SetReady(true)

	select {
	case <-ctx.Done():
		logger.Info("shutdown signal received")
	case err := <-errCh:
		if err != nil {
			logger.Error("http server error", "error", err)
		}
	}

	healthHandler.SetReady(false)
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()

	if err := dailyScheduler.Stop(shutdownCtx); err != nil {
		logger.Error("scheduler shutdown failed", "error", err)
	}
	if cdcConsumer != nil {
		if err := cdcConsumer.Stop(); err != nil {
			logger.Error("cdc consumer shutdown failed", "error", err)
		}
	}
	if err := server.Shutdown(shutdownCtx); err != nil {
		logger.Error("http server shutdown failed", "error", err)
	}
	if shutdownTracer != nil {
		if err := shutdownTracer(shutdownCtx); err != nil {
			logger.Error("tracing shutdown failed", "error", err)
		}
	}
	logger.Info("shutdown complete")
}
