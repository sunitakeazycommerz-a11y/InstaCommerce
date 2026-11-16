// Package main is the entrypoint for the reverse-etl-orchestrator service.
// This service orchestrates reverse ETL pipelines, managing subscriptions,
// transforms, and sink activations for the InstaCommerce data mesh.
package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/gorilla/mux"
	"github.com/instacommerce/go-shared/pkg/auth"
	"github.com/instacommerce/go-shared/pkg/correlation"
	"github.com/prometheus/client_golang/prometheus/promhttp"
	"github.com/redis/go-redis/v9"

	"github.com/instacommerce/reverse-etl-orchestrator/internal/api"
	"github.com/instacommerce/reverse-etl-orchestrator/internal/config"
	"github.com/instacommerce/reverse-etl-orchestrator/internal/health"
	"github.com/instacommerce/reverse-etl-orchestrator/internal/subscription"
)

func buildAPIRouter(apiHandler *api.Handler, healthChecker *health.Checker, logger *slog.Logger) http.Handler {
	apiRouter := mux.NewRouter()
	apiSubrouter := apiRouter.PathPrefix("/api/v1").Subrouter()
	apiHandler.RegisterRoutes(apiSubrouter)
	apiSubrouter.Use(auth.NewInternalAuthMiddleware(logger).Wrap)

	apiRouter.HandleFunc("/health", healthChecker.Health).Methods("GET")
	apiRouter.HandleFunc("/health/live", healthChecker.Liveness).Methods("GET")
	apiRouter.HandleFunc("/health/ready", healthChecker.Readiness).Methods("GET")
	apiRouter.Handle("/metrics", promhttp.Handler())

	return correlation.Middleware(apiRouter)
}

func newWorkerPoolFromConfig(cfg *config.Config, subscriptionSvc *subscription.Service, rdb *redis.Client, logger *slog.Logger) *subscription.WorkerPool {
	return subscription.NewWorkerPool(subscription.WorkerPoolConfig{
		Concurrency:     cfg.WorkerPoolConcurrency,
		RateLimitPerSec: cfg.RateLimitPerSec,
		RedisClient:     rdb,
		DB:              subscriptionSvc.DB(),
		Logger:          logger,
	})
}

func main() {
	// Initialize structured logging
	logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{
		Level: slog.LevelInfo,
	}))
	slog.SetDefault(logger)

	// Load configuration
	cfg, err := config.Load()
	if err != nil {
		logger.Error("failed to load configuration", "error", err)
		os.Exit(1)
	}

	logger.Info("starting reverse-etl-orchestrator",
		"version", cfg.Version,
		"environment", cfg.Environment,
	)

	// Initialize subscription service
	subscriptionSvc, err := subscription.NewService(cfg)
	if err != nil {
		logger.Error("failed to initialize subscription service", "error", err)
		os.Exit(1)
	}
	defer subscriptionSvc.Close()

	// Initialize Redis client and WorkerPool
	rdb := redis.NewClient(&redis.Options{
		Addr:         cfg.RedisAddr,
		Password:     cfg.RedisPassword,
		DB:           cfg.RedisDB,
		DialTimeout:  5 * time.Second,
		ReadTimeout:  3 * time.Second,
		WriteTimeout: 3 * time.Second,
	})
	defer rdb.Close()

	workerPool := newWorkerPoolFromConfig(cfg, subscriptionSvc, rdb, logger)
	defer workerPool.Close()

	// Initialize health checker and API handler
	healthChecker := health.NewChecker(subscriptionSvc)
	apiHandler := api.NewHandler(subscriptionSvc)

	healthRouter := mux.NewRouter()
	healthRouter.HandleFunc("/health", healthChecker.Health).Methods("GET")
	healthRouter.HandleFunc("/health/live", healthChecker.Liveness).Methods("GET")
	healthRouter.HandleFunc("/health/ready", healthChecker.Readiness).Methods("GET")

	metricsRouter := mux.NewRouter()
	metricsRouter.Handle("/metrics", promhttp.Handler())

	// Start servers
	apiServer := &http.Server{
		Addr:              cfg.APIAddr,
		Handler:           buildAPIRouter(apiHandler, healthChecker, logger),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      15 * time.Second,
		IdleTimeout:       60 * time.Second,
	}

	healthServer := &http.Server{
		Addr:              cfg.HealthAddr,
		Handler:           correlation.Middleware(healthRouter),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       5 * time.Second,
		WriteTimeout:      5 * time.Second,
	}

	metricsServer := &http.Server{
		Addr:              cfg.MetricsAddr,
		Handler:           metricsRouter,
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       5 * time.Second,
		WriteTimeout:      5 * time.Second,
	}

	errCh := make(chan error, 3)

	// Start servers in goroutines
	go func() {
		logger.Info("starting API server", "addr", cfg.APIAddr)
		if err := apiServer.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			errCh <- err
		}
	}()

	go func() {
		logger.Info("starting health server", "addr", cfg.HealthAddr)
		if err := healthServer.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			errCh <- err
		}
	}()

	go func() {
		logger.Info("starting metrics server", "addr", cfg.MetricsAddr)
		if err := metricsServer.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			errCh <- err
		}
	}()

	// Wait for shutdown signal or fatal server error
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)

	select {
	case sig := <-quit:
		logger.Info("shutting down servers", "signal", sig.String())
	case srvErr := <-errCh:
		logger.Error("server exited unexpectedly", "error", srvErr)
	}
	signal.Stop(quit)

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	// Graceful shutdown
	if err := apiServer.Shutdown(ctx); err != nil {
		logger.Error("API server shutdown error", "error", err)
	}
	if err := healthServer.Shutdown(ctx); err != nil {
		logger.Error("health server shutdown error", "error", err)
	}
	if err := metricsServer.Shutdown(ctx); err != nil {
		logger.Error("metrics server shutdown error", "error", err)
	}

	logger.Info("servers stopped")
}
