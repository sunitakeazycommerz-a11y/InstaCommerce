// Package main is the entrypoint for the reverse-etl-orchestrator service.
// This service orchestrates reverse ETL pipelines, managing subscriptions,
// transforms, and sink activations for the InstaCommerce data mesh.
package main

import (
	"context"
	"database/sql"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strings"
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
	var db *sql.DB
	if subscriptionSvc != nil {
		db = subscriptionSvc.DB()
	}
	var redisClient subscription.RedisClient
	if rdb != nil {
		redisClient = rdb
	}
	return subscription.NewWorkerPool(subscription.WorkerPoolConfig{
		Concurrency:     cfg.WorkerPoolConcurrency,
		RateLimitPerSec: cfg.RateLimitPerSec,
		RedisClient:     redisClient,
		DB:              db,
		Logger:          logger,
		RetryPolicy:     subscription.DefaultRetryPolicy(),
	})
}

func newRedisClient(cfg *config.Config) *redis.Client {
	if cfg == nil || strings.TrimSpace(cfg.RedisAddr) == "" {
		return nil
	}
	return redis.NewClient(&redis.Options{
		Addr:         strings.TrimSpace(cfg.RedisAddr),
		Password:     cfg.RedisPassword,
		DB:           cfg.RedisDB,
		DialTimeout:  5 * time.Second,
		ReadTimeout:  3 * time.Second,
		WriteTimeout: 3 * time.Second,
	})
}

func buildHealthChecker(cfg *config.Config, subscriptionSvc *subscription.Service, rdb *redis.Client) *health.Checker {
	var opts []health.Option
	if cfg != nil && cfg.Version != "" {
		opts = append(opts, health.WithVersion(cfg.Version))
	}
	if cfg != nil && strings.TrimSpace(cfg.RedisAddr) != "" {
		if rdb != nil {
			opts = append(opts, health.WithRedis(health.PingerFunc(func(ctx context.Context) error {
				return rdb.Ping(ctx).Err()
			})))
		} else {
			opts = append(opts, health.WithRedis(health.PingerFunc(func(context.Context) error {
				return errors.New("redis client not initialized")
			})))
		}
	}
	var dbPinger health.Pinger
	if subscriptionSvc != nil {
		dbPinger = subscriptionSvc
	}
	return health.NewChecker(dbPinger, opts...)
}

func main() {
	logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{
		Level: slog.LevelInfo,
	}))
	slog.SetDefault(logger)

	cfg, err := config.Load()
	if err != nil {
		logger.Error("failed to load configuration", "error", err)
		os.Exit(1)
	}

	logger.Info("starting reverse-etl-orchestrator",
		"version", cfg.Version,
		"environment", cfg.Environment,
	)

	subscriptionSvc, err := subscription.NewService(cfg)
	if err != nil {
		logger.Error("failed to initialize subscription service", "error", err)
		os.Exit(1)
	}
	defer subscriptionSvc.Close()

	rdb := newRedisClient(cfg)
	if rdb != nil {
		defer rdb.Close()
	}

	workerPool := newWorkerPoolFromConfig(cfg, subscriptionSvc, rdb, logger)
	defer workerPool.Close()

	healthChecker := buildHealthChecker(cfg, subscriptionSvc, rdb)
	apiHandler := api.NewHandler(subscriptionSvc)

	healthRouter := mux.NewRouter()
	healthRouter.HandleFunc("/health", healthChecker.Health).Methods("GET")
	healthRouter.HandleFunc("/health/live", healthChecker.Liveness).Methods("GET")
	healthRouter.HandleFunc("/health/ready", healthChecker.Readiness).Methods("GET")

	metricsRouter := mux.NewRouter()
	metricsRouter.Handle("/metrics", promhttp.Handler())

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
