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

	"github.com/instacommerce/dispatch-optimizer-service/handler"
	"github.com/instacommerce/dispatch-optimizer-service/optimizer"
	"github.com/instacommerce/go-shared/pkg/correlation"
	"github.com/instacommerce/go-shared/pkg/health"
	"github.com/prometheus/client_golang/prometheus/promhttp"
	"go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp"
)

const (
	maxBodyBytes = 1 << 20
	serviceName  = "dispatch-optimizer-service"
)

func main() {
	logger := initLogger()
	slog.SetDefault(logger)
	shutdownTracer := initTracerProvider(context.Background(), logger)
	metrics := newMetrics()

	port := os.Getenv("PORT")
	if port == "" {
		port = os.Getenv("SERVER_PORT")
	}
	if port == "" {
		port = "8102"
	}

	mux := http.NewServeMux()
	hHandler := health.NewHandler()
	hHandler.SetReady(true)
	hHandler.RegisterRoutes(mux)
	mux.Handle("/metrics", promhttp.Handler())

	assignHandler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		handleAssign(w, r, logger, metrics)
	})
	mux.Handle("/optimize/assign", otelhttp.NewHandler(
		instrumentHandler("optimize_assign", logger, metrics, assignHandler),
		"OptimizeAssign",
	))

	solver := optimizer.NewSolver(optimizer.SolverConfig{})
	matcher := optimizer.NewBipartiteMatcher(nil, solver)
	publisher := &handler.NoopEventPublisher{Logger: logger}
	riderLocker := handler.NewInMemoryRiderMutex()

	mux.Handle("/v2/optimize/assign", otelhttp.NewHandler(
		instrumentHandler("v2_optimize_assign", logger, metrics, handler.HandleAssignV2(solver, logger)),
		"OptimizeAssignV2",
	))
	mux.Handle("/dispatch/assign", otelhttp.NewHandler(
		instrumentHandler("dispatch_assign", logger, metrics, handler.HandleDispatchAssignWithLocker(matcher, publisher, riderLocker, logger)),
		"DispatchAssign",
	))
	mux.Handle("/dispatch/response", otelhttp.NewHandler(
		instrumentHandler("dispatch_response", logger, metrics, handler.HandleRiderResponse(matcher, publisher, riderLocker, logger)),
		"DispatchResponse",
	))

	server := &http.Server{
		Addr:              ":" + port,
		Handler:           correlation.Middleware(mux),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      15 * time.Second,
		IdleTimeout:       60 * time.Second,
	}

	errCh := make(chan error, 1)
	go func() {
		logger.Info("dispatch optimizer service listening", "addr", server.Addr)
		if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			errCh <- err
		}
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)

	exitCode := 0
	select {
	case sig := <-quit:
		logger.Info("received signal, shutting down gracefully", "signal", sig.String())
	case err := <-errCh:
		logger.Error("server failed", "error", err)
		exitCode = 1
	}

	hHandler.SetReady(false)
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	if err := server.Shutdown(ctx); err != nil {
		logger.Error("server shutdown failed", "error", err)
		exitCode = 1
	}
	if shutdownTracer != nil {
		if err := shutdownTracer(ctx); err != nil {
			logger.Error("tracer shutdown failed", "error", err)
			exitCode = 1
		}
	}
	logger.Info("server stopped")
	if exitCode != 0 {
		os.Exit(exitCode)
	}
}
