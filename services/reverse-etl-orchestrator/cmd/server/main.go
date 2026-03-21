// Package main is the entrypoint for the reverse-etl-orchestrator service.
// This service orchestrates reverse ETL pipelines, managing subscriptions,
// transforms, and sink activations for the InstaCommerce data mesh.
package main

import (
	"context"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/gorilla/mux"
	"github.com/prometheus/client_golang/prometheus/promhttp"

	"github.com/instacommerce/reverse-etl-orchestrator/internal/api"
	"github.com/instacommerce/reverse-etl-orchestrator/internal/config"
	"github.com/instacommerce/reverse-etl-orchestrator/internal/health"
	"github.com/instacommerce/reverse-etl-orchestrator/internal/subscription"
)

func main() {
	// Initialize structured logging
	logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{
		Level: slog.LevelInfo,
	}))
	slog.SetDefault(logger)

	// Load configuration
	cfg, err := config.Load()
	if err != nil {
		slog.Error("failed to load configuration", "error", err)
		os.Exit(1)
	}

	slog.Info("starting reverse-etl-orchestrator",
		"version", cfg.Version,
		"environment", cfg.Environment,
	)

	// Initialize subscription service
	subscriptionSvc, err := subscription.NewService(cfg)
	if err != nil {
		slog.Error("failed to initialize subscription service", "error", err)
		os.Exit(1)
	}
	defer subscriptionSvc.Close()

	// Initialize health checker
	healthChecker := health.NewChecker(subscriptionSvc)

	// Initialize API handler
	apiHandler := api.NewHandler(subscriptionSvc)

	// Setup routers
	apiRouter := mux.NewRouter()
	apiHandler.RegisterRoutes(apiRouter.PathPrefix("/api/v1").Subrouter())

	healthRouter := mux.NewRouter()
	healthRouter.HandleFunc("/health", healthChecker.Health).Methods("GET")
	healthRouter.HandleFunc("/health/live", healthChecker.Liveness).Methods("GET")
	healthRouter.HandleFunc("/health/ready", healthChecker.Readiness).Methods("GET")

	metricsRouter := mux.NewRouter()
	metricsRouter.Handle("/metrics", promhttp.Handler())

	// Start servers
	apiServer := &http.Server{
		Addr:         cfg.APIAddr,
		Handler:      apiRouter,
		ReadTimeout:  15 * time.Second,
		WriteTimeout: 15 * time.Second,
		IdleTimeout:  60 * time.Second,
	}

	healthServer := &http.Server{
		Addr:         cfg.HealthAddr,
		Handler:      healthRouter,
		ReadTimeout:  5 * time.Second,
		WriteTimeout: 5 * time.Second,
	}

	metricsServer := &http.Server{
		Addr:         cfg.MetricsAddr,
		Handler:      metricsRouter,
		ReadTimeout:  5 * time.Second,
		WriteTimeout: 5 * time.Second,
	}

	// Start servers in goroutines
	go func() {
		slog.Info("starting API server", "addr", cfg.APIAddr)
		if err := apiServer.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			slog.Error("API server error", "error", err)
		}
	}()

	go func() {
		slog.Info("starting health server", "addr", cfg.HealthAddr)
		if err := healthServer.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			slog.Error("health server error", "error", err)
		}
	}()

	go func() {
		slog.Info("starting metrics server", "addr", cfg.MetricsAddr)
		if err := metricsServer.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			slog.Error("metrics server error", "error", err)
		}
	}()

	// Wait for shutdown signal
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	slog.Info("shutting down servers")

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	// Graceful shutdown
	if err := apiServer.Shutdown(ctx); err != nil {
		slog.Error("API server shutdown error", "error", err)
	}
	if err := healthServer.Shutdown(ctx); err != nil {
		slog.Error("health server shutdown error", "error", err)
	}
	if err := metricsServer.Shutdown(ctx); err != nil {
		slog.Error("metrics server shutdown error", "error", err)
	}

	slog.Info("servers stopped")
}
