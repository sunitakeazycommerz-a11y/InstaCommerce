package main

import (
	"context"
	"database/sql"
	"errors"
	"net/http"
	"net/http/httptest"
	"os/signal"
	"syscall"
	"time"

	"github.com/instacommerce/go-shared/pkg/auth"
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
	if err := ensureReconciliationSchema(ctx, db); err != nil {
		logger.Error("failed to ensure reconciliation schema", "error", err)
		return
	}

	eventPub := reconciliation.NewEventPublisher(cfg.KafkaBrokers, cfg.KafkaTopic, logger)
	defer eventPub.Close()

	dbReconciler := reconciliation.NewDBReconciler(db, logger, otel.Tracer(cfg.ServiceName), eventPub)
	dailyScheduler := reconciliation.NewDailyScheduler(dbReconciler, logger, cfg.Schedule)

	var cdcConsumer *cdc.CDCConsumer
	if cfg.CDCEnabled && len(cfg.KafkaBrokers) > 0 {
		cdcConsumer, err = cdc.NewCDCConsumer(ctx, cdc.CDCConsumerConfig{
			KafkaBrokers:      cfg.KafkaBrokers,
			KafkaGroupID:      cfg.CDCGroupID,
			CDCTopics:         cfg.CDCTopics,
			DLTTopic:          cfg.CDCDLTTopic,
			MaxProcessRetries: cfg.CDCMaxRetries,
			DB:                db,
			MinBytes:          cfg.CDCMinBytes,
			MaxBytes:          cfg.CDCMaxBytes,
			MaxWait:           cfg.CDCMaxWait,
			CommitInterval:    cfg.CDCCommitInterval,
			BatchSize:         cfg.CDCBatchSize,
			BatchTimeout:      cfg.CDCBatchTimeout,
		}, logger)
		if err != nil {
			logger.Error("failed to create cdc consumer", "error", err)
			cdcConsumer = nil
		}
	}

	reconciliationHandler := api.NewReconciliationHandler(dbReconciler, logger)
	healthChecks := []health.Check{
		{
			Name:  "postgres",
			Check: func(c context.Context) error { return db.PingContext(c) },
		},
	}
	if len(cfg.KafkaBrokers) > 0 {
		healthChecks = append(healthChecks, health.Check{
			Name:  "kafka",
			Check: func(c context.Context) error { return health.CheckTCPBrokers(c, cfg.KafkaBrokers, 2*time.Second) },
		})
	}
	healthHandler := health.NewHandler(healthChecks...)
	authMiddleware := auth.NewInternalAuthMiddleware(logger)
	mux := newHTTPMux(healthHandler, reconciliationHandler, authMiddleware, db, cfg.KafkaBrokers)

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

func newHTTPMux(
	healthHandler *health.Handler,
	reconciliationHandler *api.ReconciliationHandler,
	authMiddleware *auth.InternalAuthMiddleware,
	db *sql.DB,
	kafkaBrokers []string,
) *http.ServeMux {
	mux := http.NewServeMux()
	readyFn := func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if healthHandler != nil {
			rec := httptest.NewRecorder()
			healthHandler.ReadyHandler()(rec, r)
			if rec.Code != http.StatusOK {
				w.WriteHeader(http.StatusServiceUnavailable)
				_, _ = w.Write([]byte(`{"status":"not_ready"}`))
				return
			}
		}
		checkCtx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
		defer cancel()
		if db != nil {
			if err := db.PingContext(checkCtx); err != nil {
				w.WriteHeader(http.StatusServiceUnavailable)
				_, _ = w.Write([]byte(`{"status":"not_ready","error":"postgres unavailable"}`))
				return
			}
		}
		if len(kafkaBrokers) > 0 {
			if err := health.CheckTCPBrokers(checkCtx, kafkaBrokers, 2*time.Second); err != nil {
				w.WriteHeader(http.StatusServiceUnavailable)
				_, _ = w.Write([]byte(`{"status":"not_ready","error":"kafka unavailable"}`))
				return
			}
		}
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"status":"ready"}`))
	}

	if healthHandler != nil {
		mux.HandleFunc("/health", healthHandler.HealthHandler())
		mux.HandleFunc("/health/live", healthHandler.LiveHandler())
	}
	mux.HandleFunc("/health/ready", readyFn)
	mux.HandleFunc("/ready", readyFn)
	mux.Handle("/metrics", promhttp.Handler())

	wrap := func(fn http.HandlerFunc) http.Handler {
		if authMiddleware != nil {
			return authMiddleware.Wrap(fn)
		}
		return fn
	}
	if reconciliationHandler != nil {
		mux.Handle("/reconciliation/runs", wrap(reconciliationHandler.HandleListRuns))
		mux.Handle("/reconciliation/pending", wrap(reconciliationHandler.HandleGetPendingMismatches))
		mux.Handle("/pending", wrap(reconciliationHandler.HandleGetPendingMismatches))
		mux.Handle("/reconciliation/runs/{runID}/mismatches", wrap(reconciliationHandler.HandleGetRunMismatches))
		mux.Handle("/reconciliation/mismatches/{mismatchID}/review", wrap(reconciliationHandler.HandleReviewMismatch))
		mux.Handle("/reconciliation/mismatches/{mismatchID}/fix", wrap(reconciliationHandler.HandleApplyFix))
	}
	return mux
}
