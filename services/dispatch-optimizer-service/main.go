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
	"github.com/instacommerce/go-shared/pkg/auth"
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

	solver := optimizer.NewSolver(optimizer.SolverConfig{})
	matcher := optimizer.NewBipartiteMatcher(nil, solver)
	publisher, kafkaBrokers, closePublisher := handler.NewEventPublisherFromEnv(logger)
	defer func() {
		if err := closePublisher(); err != nil {
			logger.Warn("publisher close failed", "error", err)
		}
	}()
	riderLocker, redisClient, closeRedis := handler.NewRiderMutexFromEnv(logger)
	defer func() {
		if err := closeRedis(); err != nil {
			logger.Warn("redis close failed", "error", err)
		}
	}()

	var healthChecks []health.Check
	if redisClient != nil {
		healthChecks = append(healthChecks, health.Check{
			Name: "redis",
			Check: func(ctx context.Context) error {
				return redisClient.Ping(ctx).Err()
			},
		})
	}
	if len(kafkaBrokers) > 0 {
		healthChecks = append(healthChecks, health.Check{
			Name: "kafka",
			Check: func(ctx context.Context) error {
				return health.CheckTCPBrokers(ctx, kafkaBrokers, 2*time.Second)
			},
		})
	}
	hHandler := health.NewHandler(healthChecks...)
	hHandler.SetReady(true)

	authMiddleware := auth.NewInternalAuthMiddleware(logger)
	mux := newHTTPMux(hHandler, authMiddleware, solver, matcher, publisher, riderLocker, logger, metrics)

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

func newHTTPMux(
	hHandler *health.Handler,
	authMiddleware *auth.InternalAuthMiddleware,
	solver *optimizer.Solver,
	matcher *optimizer.BipartiteMatcher,
	publisher handler.EventPublisher,
	riderLocker handler.RiderMutex,
	logger *slog.Logger,
	metrics *serviceMetrics,
) *http.ServeMux {
	mux := http.NewServeMux()
	if hHandler != nil {
		hHandler.RegisterRoutes(mux)
	}
	mux.Handle("/metrics", promhttp.Handler())

	wrap := func(next http.Handler) http.Handler {
		if authMiddleware != nil {
			return authMiddleware.Wrap(next)
		}
		return next
	}

	assignHandler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		handleAssign(w, r, logger, metrics)
	})
	mux.Handle("/optimize/assign", wrap(otelhttp.NewHandler(
		instrumentHandler("optimize_assign", logger, metrics, assignHandler),
		"OptimizeAssign",
	)))
	mux.Handle("/v2/optimize/assign", wrap(otelhttp.NewHandler(
		instrumentHandler("v2_optimize_assign", logger, metrics, handler.HandleAssignV2(solver, logger)),
		"OptimizeAssignV2",
	)))
	mux.Handle("/dispatch/assign", wrap(otelhttp.NewHandler(
		instrumentHandler("dispatch_assign", logger, metrics, handler.HandleDispatchAssignWithLocker(matcher, publisher, riderLocker, logger)),
		"DispatchAssign",
	)))
	mux.Handle("/dispatch/response", wrap(otelhttp.NewHandler(
		instrumentHandler("dispatch_response", logger, metrics, handler.HandleRiderResponse(matcher, publisher, riderLocker, logger)),
		"DispatchResponse",
	)))
	return mux
}
