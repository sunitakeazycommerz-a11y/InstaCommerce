package main

import (
	"context"
	"database/sql"
	"fmt"
	"log/slog"
	"os"
	"strings"
	"time"

	"github.com/instacommerce/go-shared/pkg/observability"
	"github.com/prometheus/client_golang/prometheus"
)

type Metrics struct {
	mismatches   prometheus.Counter
	fixed        prometheus.Counter
	manualReview prometheus.Counter
}

func NewMetrics() *Metrics {
	m := &Metrics{
		mismatches: prometheus.NewCounter(prometheus.CounterOpts{
			Name: "reconciliation_mismatches_total",
			Help: "Total number of reconciliation mismatches found.",
		}),
		fixed: prometheus.NewCounter(prometheus.CounterOpts{
			Name: "reconciliation_fixed_total",
			Help: "Total number of reconciliation mismatches fixed automatically.",
		}),
		manualReview: prometheus.NewCounter(prometheus.CounterOpts{
			Name: "reconciliation_manual_review_total",
			Help: "Total number of reconciliation mismatches sent for manual review.",
		}),
	}
	prometheus.MustRegister(m.mismatches, m.fixed, m.manualReview)
	return m
}

func (m *Metrics) RecordMismatch()     { m.mismatches.Inc() }
func (m *Metrics) RecordFixed()        { m.fixed.Inc() }
func (m *Metrics) RecordManualReview() { m.manualReview.Inc() }

func newLogger(level string) *slog.Logger {
	handler := slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: parseLogLevel(level)})
	return slog.New(handler)
}

func parseLogLevel(level string) slog.Level {
	switch strings.ToLower(strings.TrimSpace(level)) {
	case "debug":
		return slog.LevelDebug
	case "warn", "warning":
		return slog.LevelWarn
	case "error":
		return slog.LevelError
	default:
		return slog.LevelInfo
	}
}

func initTracer(ctx context.Context, serviceName string, logger *slog.Logger) (func(context.Context) error, error) {
	providers, err := observability.InitOTel(ctx, observability.OTelConfig{
		ServiceName:      serviceName,
		Protocol:         observability.OTelProtocolGRPC,
		ResolveEnv:       true,
		NoopIfNoEndpoint: true,
		FallbackNoop:     true,
	}, logger)
	if err != nil {
		return nil, err
	}
	return providers.Shutdown, nil
}

func initDatabase(ctx context.Context, cfg Config, logger *slog.Logger) (*sql.DB, error) {
	dsn := fmt.Sprintf("postgres://%s:%s@%s:%s/%s?sslmode=disable&connect_timeout=%d",
		cfg.DBUser, cfg.DBPassword, cfg.DBHost, cfg.DBPort, cfg.DBName, int(cfg.DBConnTimeout.Seconds()),
	)
	db, err := sql.Open("postgres", dsn)
	if err != nil {
		return nil, fmt.Errorf("failed to open database: %w", err)
	}
	db.SetMaxOpenConns(cfg.DBMaxConnections)
	db.SetMaxIdleConns(cfg.DBMaxConnections / 2)
	db.SetConnMaxLifetime(30 * time.Minute)

	connCtx, cancel := context.WithTimeout(ctx, cfg.DBConnTimeout)
	defer cancel()
	if err := db.PingContext(connCtx); err != nil {
		db.Close()
		return nil, fmt.Errorf("failed to verify database connection: %w", err)
	}
	logger.Info("database connection pool initialized", "host", cfg.DBHost, "port", cfg.DBPort, "database", cfg.DBName)
	return db, nil
}
