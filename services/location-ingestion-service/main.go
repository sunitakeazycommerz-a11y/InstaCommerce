package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"sync"
	"syscall"
	"time"

	"github.com/instacommerce/go-shared/pkg/auth"
	"github.com/instacommerce/go-shared/pkg/correlation"
	"github.com/instacommerce/go-shared/pkg/health"
	"github.com/instacommerce/location-ingestion-service/handler"
	"github.com/instacommerce/location-ingestion-service/store"
	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promhttp"
	"github.com/redis/go-redis/v9"
	"github.com/segmentio/kafka-go"
	"go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp"
	"go.opentelemetry.io/otel"
)

type positionStoreAdapter struct {
	store *store.LatestPositionStore
}

func (a positionStoreAdapter) Update(ctx context.Context, u handler.LocationUpdate) error {
	err := a.store.Update(ctx, store.LocationUpdate{
		RiderID:     u.RiderID,
		Lat:         u.Lat,
		Lng:         u.Lng,
		Accuracy:    u.Accuracy,
		Speed:       u.Speed,
		Heading:     u.Heading,
		Timestamp:   u.Timestamp,
		BatteryPct:  u.BatteryPct,
		H3Index:     u.H3Index,
		HasSpeed:    u.HasSpeed,
		HasHeading:  u.HasHeading,
		HasAccuracy: u.HasAccuracy,
	})
	if errors.Is(err, store.ErrOutOfOrderPing) {
		return fmt.Errorf("%w: %w", handler.ErrOutOfOrderPing, err)
	}
	return err
}

func main() {
	cfg := loadConfig()
	logger := newLogger(cfg)
	metrics := handler.NewLocationMetrics(prometheus.DefaultRegisterer)

	ctx := context.Background()
	tp := initTracing(ctx, cfg, logger)
	defer func() {
		if tp != nil {
			shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			_ = tp.Shutdown(shutdownCtx)
		}
	}()

	redisClient := redis.NewClient(&redis.Options{
		Addr:     cfg.RedisAddr,
		Password: cfg.RedisPassword,
		DB:       cfg.RedisDB,
	})
	posStore, err := store.NewLatestPositionStoreWithPrefix(redisClient, cfg.RedisKeyPrefix, cfg.RedisTTL)
	if err != nil {
		logger.Error("failed to initialize redis store", "error", err)
		os.Exit(1)
	}

	var kafkaWriter, geofenceWriter *kafka.Writer
	var geofencePub handler.GeofencePublisher
	if len(cfg.KafkaBrokers) > 0 {
		kafkaWriter = newKafkaWriter(cfg.KafkaBrokers, cfg.KafkaTopic, cfg.KafkaWriteTimeout)
		geofenceWriter = newKafkaWriter(cfg.KafkaBrokers, handler.DefaultGeofenceTopic, cfg.KafkaWriteTimeout)
		geofencePub = handler.NewKafkaGeofencePublisher(
			handler.NewKafkaWriterProducer(geofenceWriter),
			handler.DefaultGeofenceTopic,
			logger,
		)
	}

	batcher, err := handler.NewLocationBatcher(
		handler.NewKafkaWriterProducer(kafkaWriter),
		cfg.KafkaTopic,
		cfg.KafkaBatchSize,
		cfg.KafkaBatchTimeout,
		logger,
		handler.WithBatchBufferCapacity(cfg.KafkaBuffer),
		handler.WithBatchWriteTimeout(cfg.KafkaWriteTimeout),
		handler.WithBatchDropHook(func(reason string, count int) {
			metrics.UpdatesDropped.WithLabelValues("kafka", reason).Add(float64(count))
		}),
	)
	if err != nil {
		logger.Error("failed to initialize kafka batcher", "error", err)
		os.Exit(1)
	}

	h3Res := cfg.H3Resolution
	if h3Res <= 0 {
		h3Res = 8
	}
	geofencer := handler.NewGeofenceChecker(h3Res)

	ingestHandler := handler.NewIngestHandler(
		handler.IngestConfig{
			MaxBodyBytes:     cfg.MaxBodyBytes,
			WebSocketReadMax: cfg.WebSocketReadMax,
			ProcessTimeout:   cfg.ProcessTimeout,
			H3Resolution:     cfg.H3Resolution,
		},
		batcher,
		positionStoreAdapter{store: posStore},
		geofencer,
		logger,
		metrics,
		handler.WithGeofencePublisher(geofencePub),
		handler.WithTelemetrySmoother(handler.NewRiderTelemetrySmoother()),
		handler.WithTracer(otel.Tracer(cfg.ServiceName)),
	)

	backgroundCtx, cancel := context.WithCancel(context.Background())
	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		batcher.Start(backgroundCtx)
	}()

	healthHandler := health.NewHandler(
		health.Check{
			Name:  "redis",
			Check: func(c context.Context) error { return redisClient.Ping(c).Err() },
		},
		health.Check{
			Name: "kafka",
			Check: func(c context.Context) error {
				if kafkaWriter == nil {
					return errors.New("kafka disabled")
				}
				return checkKafka(c, cfg.KafkaBrokers)
			},
		},
	)
	healthHandler.SetReady(true)

	mux := newHTTPMux(ingestHandler, healthHandler, logger)
	server := &http.Server{
		Addr:              ":" + cfg.Port,
		Handler:           otelhttp.NewHandler(correlation.Middleware(mux), "http.server"),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		WriteTimeout:      10 * time.Second,
		IdleTimeout:       60 * time.Second,
	}

	errCh := make(chan error, 1)
	go func() {
		logger.Info("location ingestion service listening", "addr", server.Addr)
		if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			errCh <- err
		}
		close(errCh)
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	select {
	case sig := <-quit:
		logger.Info("shutdown signal received", "signal", sig.String())
	case err := <-errCh:
		if err != nil {
			logger.Error("server error", "error", err)
		}
	}
	signal.Stop(quit)

	shutdownServer(server, healthHandler, cfg.ShutdownTimeout, logger)
	ingestHandler.Close()
	cancel()
	wg.Wait()

	if kafkaWriter != nil {
		_ = kafkaWriter.Close()
	}
	if geofenceWriter != nil {
		_ = geofenceWriter.Close()
	}
	_ = redisClient.Close()
	logger.Info("service stopped")
}

func newKafkaWriter(brokers []string, topic string, writeTimeout time.Duration) *kafka.Writer {
	return &kafka.Writer{
		Addr:                   kafka.TCP(brokers...),
		Topic:                  topic,
		Balancer:               &kafka.Hash{},
		RequiredAcks:           kafka.RequireAll,
		AllowAutoTopicCreation: true,
		WriteTimeout:           writeTimeout,
	}
}

func newHTTPMux(locHandler *handler.LocationHandler, healthHandler *health.Handler, logger *slog.Logger) *http.ServeMux {
	mux := http.NewServeMux()
	mux.Handle("/metrics", promhttp.Handler())
	healthHandler.RegisterRoutes(mux)
	mux.HandleFunc("/ready", healthHandler.ReadyHandler())

	authMiddleware := auth.NewInternalAuthMiddleware(logger)
	mux.Handle("/ingest", authMiddleware.Wrap(http.HandlerFunc(locHandler.HandleIngest)))
	mux.Handle("/ingest/location", authMiddleware.Wrap(http.HandlerFunc(locHandler.HandleIngest)))
	mux.Handle("/ingest/batch", authMiddleware.Wrap(http.HandlerFunc(locHandler.HandleBatchIngest)))
	mux.Handle("/batch", authMiddleware.Wrap(http.HandlerFunc(locHandler.HandleBatchIngest)))
	mux.Handle("/ingest/ws", authMiddleware.Wrap(http.HandlerFunc(locHandler.HandleWebSocket)))
	return mux
}
