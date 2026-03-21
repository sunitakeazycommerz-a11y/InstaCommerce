package main

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"net/url"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promhttp"
	"github.com/robfig/cron/v3"
	"github.com/segmentio/kafka-go"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracegrpc"
	"go.opentelemetry.io/otel/exporters/stdout/stdouttrace"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	"go.opentelemetry.io/otel/semconv/v1.21.0"
	"go.opentelemetry.io/otel/trace"

	"reconciliation-engine/pkg/cdc"
)

const (
	defaultPort                  = "8107"
	defaultSchedule              = "5m"
	defaultKafkaTopic            = "reconciliation.events"
	defaultReconciliationTimeout = 2 * time.Minute
)

type Config struct {
	Port                   string
	ServiceName            string
	LogLevel               string
	Schedule               string
	RunOnStartup           bool
	ReconciliationTimeout  time.Duration
	PSPExportPath           string
	LedgerPath              string
	LedgerOutputPath        string
	FixStatePath            string
	KafkaBrokers            []string
	KafkaTopic              string
	KafkaClientID           string
	CDCEnabled              bool        // Enable CDC consumer
	CDCTopics               []string    // Topics to consume from (e.g., reconciliation.cdc)
	CDCGroupID              string      // Consumer group for CDC
	CDCMinBytes             int
	CDCMaxBytes             int
	CDCMaxWait              time.Duration
	CDCCommitInterval       time.Duration
	CDCBatchSize            int
	CDCBatchTimeout         time.Duration
}

type Transaction struct {
	ID          string `json:"id"`
	AmountCents int64  `json:"amount_cents"`
	Currency    string `json:"currency"`
	Status      string `json:"status,omitempty"`
}

type LedgerEntry struct {
	ID          string    `json:"id"`
	AmountCents int64     `json:"amount_cents"`
	Currency    string    `json:"currency"`
	Type        string    `json:"type"`
	Source      string    `json:"source,omitempty"`
	UpdatedAt   time.Time `json:"updated_at"`
}

type Mismatch struct {
	TransactionID string
	Type          string
	Reason        string
	Fixable       bool
	PSP           *Transaction
	Ledger        *LedgerEntry
}

type ReconciliationCounts struct {
	Mismatches   int
	Fixed        int
	ManualReview int
}

type ReconciliationEvent struct {
	EventID       string                `json:"event_id"`
	RunID         string                `json:"run_id"`
	EventType     string                `json:"event_type"`
	OccurredAt    time.Time             `json:"occurred_at"`
	TransactionID string                `json:"transaction_id,omitempty"`
	MismatchType  string                `json:"mismatch_type,omitempty"`
	Reason        string                `json:"reason,omitempty"`
	FixApplied    bool                  `json:"fix_applied,omitempty"`
	Counts        *ReconciliationCounts `json:"counts,omitempty"`
}

type Metrics struct {
	mismatches   prometheus.Counter
	fixed        prometheus.Counter
	manualReview prometheus.Counter
}

type PSPSource struct {
	path   string
	logger *slog.Logger
}

type LedgerStore struct {
	mu         sync.Mutex
	entries    map[string]LedgerEntry
	outputPath string
	logger     *slog.Logger
}

type FixRecord struct {
	ID            string    `json:"id"`
	AppliedAt     time.Time `json:"applied_at"`
	MismatchType  string    `json:"mismatch_type"`
	TransactionID string    `json:"transaction_id"`
}

type FixRegistry struct {
	mu      sync.Mutex
	records map[string]FixRecord
	path    string
	logger  *slog.Logger
}

type KafkaPublisher struct {
	enabled bool
	writer  *kafka.Writer
	logger  *slog.Logger
}

type Reconciler struct {
	logger       *slog.Logger
	tracer       trace.Tracer
	metrics      *Metrics
	pspSource    *PSPSource
	ledgerStore  *LedgerStore
	fixRegistry  *FixRegistry
	publisher    *KafkaPublisher
	timeout      time.Duration
	running      atomic.Bool
}

type Scheduler struct {
	stop func(context.Context) error
}

type errorResponse struct {
	Error string `json:"error"`
}

func main() {
	cfg := loadConfig()
	logger := newLogger(cfg.LogLevel).With("service", cfg.ServiceName)

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	shutdownTracer, err := initTracer(ctx, cfg.ServiceName, logger)
	if err != nil {
		logger.Error("failed to initialize tracing", "error", err)
	}

	metrics := NewMetrics()

	ledgerStore, err := NewLedgerStore(cfg, logger)
	if err != nil {
		logger.Warn("ledger load failed", "error", err)
	}

	fixRegistry, err := NewFixRegistry(cfg.FixStatePath, logger)
	if err != nil {
		logger.Warn("fix registry load failed", "error", err)
	}

	pspSource := NewPSPSource(cfg.PSPExportPath, logger)
	publisher := NewKafkaPublisher(cfg, logger)

	reconciler := &Reconciler{
		logger:      logger,
		tracer:      otel.Tracer(cfg.ServiceName),
		metrics:     metrics,
		pspSource:   pspSource,
		ledgerStore: ledgerStore,
		fixRegistry: fixRegistry,
		publisher:   publisher,
		timeout:     cfg.ReconciliationTimeout,
	}

	// Initialize CDC consumer for payment ledger change event processing
	var cdcConsumer *cdc.CDCConsumer
	if cfg.CDCEnabled && len(cfg.KafkaBrokers) > 0 {
		cdcConfig := cdc.CDCConsumerConfig{
			KafkaBrokers:   cfg.KafkaBrokers,
			KafkaGroupID:   cfg.CDCGroupID,
			CDCTopic:       "reconciliation.cdc",
			MinBytes:       cfg.CDCMinBytes,
			MaxBytes:       cfg.CDCMaxBytes,
			MaxWait:        cfg.CDCMaxWait,
			CommitInterval: cfg.CDCCommitInterval,
			BatchSize:      cfg.CDCBatchSize,
			BatchTimeout:   cfg.CDCBatchTimeout,
		}
		var cdcErr error
		cdcConsumer, cdcErr = cdc.NewCDCConsumer(ctx, cdcConfig, logger)
		if cdcErr != nil {
			logger.Error("failed to create cdc consumer", "error", cdcErr)
			// Continue without CDC; it's optional
			cdcConsumer = nil
		} else {
			logger.Info("cdc consumer created", "group_id", cfg.CDCGroupID)
		}
	}

	ready := &atomic.Bool{}

	mux := http.NewServeMux()
	mux.HandleFunc("/health", handleHealth)
	mux.HandleFunc("/health/live", handleHealth)
	mux.HandleFunc("/ready", handleReady(ready))
	mux.HandleFunc("/health/ready", handleReady(ready))
	mux.Handle("/metrics", promhttp.Handler())

	server := &http.Server{
		Addr:              ":" + cfg.Port,
		Handler:           mux,
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

	scheduler, err := StartScheduler(ctx, cfg.Schedule, cfg.RunOnStartup, logger, reconciler.Run)
	if err != nil {
		logger.Error("failed to start scheduler", "error", err)
		return
	}

	// Start CDC consumer if enabled
	if cdcConsumer != nil {
		if err := cdcConsumer.Start(ctx); err != nil {
			logger.Error("failed to start cdc consumer", "error", err)
		} else {
			logger.Info("cdc consumer started")
		}
	}

	ready.Store(true)

	select {
	case <-ctx.Done():
		logger.Info("shutdown signal received")
	case err := <-errCh:
		if err != nil {
			logger.Error("http server error", "error", err)
		}
	}

	shutdownCtx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()

	if err := scheduler.Stop(shutdownCtx); err != nil {
		logger.Error("scheduler shutdown failed", "error", err)
	}

	// Stop CDC consumer
	if cdcConsumer != nil {
		if err := cdcConsumer.Stop(); err != nil {
			logger.Error("cdc consumer shutdown failed", "error", err)
		}
	}

	if err := server.Shutdown(shutdownCtx); err != nil {
		logger.Error("http server shutdown failed", "error", err)
	}

	if err := publisher.Close(); err != nil {
		logger.Error("kafka publisher shutdown failed", "error", err)
	}

	if shutdownTracer != nil {
		if err := shutdownTracer(shutdownCtx); err != nil {
			logger.Error("tracing shutdown failed", "error", err)
		}
	}

	logger.Info("shutdown complete")
}

func loadConfig() Config {
	port := getenv("PORT", "")
	if port == "" {
		port = getenv("SERVER_PORT", defaultPort)
	}

	ledgerPath := getenv("LEDGER_PATH", "")
	outputPath := getenv("LEDGER_OUTPUT_PATH", "")
	if outputPath == "" {
		outputPath = ledgerPath
	}

	return Config{
		Port:                  port,
		ServiceName:           getenv("OTEL_SERVICE_NAME", "reconciliation-engine"),
		LogLevel:              getenv("LOG_LEVEL", "info"),
		Schedule:              getenv("RECONCILIATION_SCHEDULE", defaultSchedule),
		RunOnStartup:          getenvBool("RECONCILIATION_RUN_ON_STARTUP", true),
		ReconciliationTimeout: getenvDuration("RECONCILIATION_TIMEOUT", defaultReconciliationTimeout),
		PSPExportPath:         getenv("PSP_EXPORT_PATH", ""),
		LedgerPath:            ledgerPath,
		LedgerOutputPath:      outputPath,
		FixStatePath:          getenv("FIX_STATE_PATH", ""),
		KafkaBrokers:          splitCSV(getenv("KAFKA_BROKERS", "")),
		KafkaTopic:            getenv("KAFKA_TOPIC", defaultKafkaTopic),
		KafkaClientID:         getenv("KAFKA_CLIENT_ID", "reconciliation-engine"),
		CDCEnabled:            getenvBool("CDC_ENABLED", true),
		CDCTopics:             splitCSV(getenv("CDC_TOPICS", "reconciliation.cdc")),
		CDCGroupID:            getenv("CDC_GROUP_ID", "reconciliation-cdc-consumer"),
		CDCMinBytes:           getenvInt("CDC_MIN_BYTES", 10*1024),
		CDCMaxBytes:           getenvInt("CDC_MAX_BYTES", 10*1024*1024),
		CDCMaxWait:            getenvDuration("CDC_MAX_WAIT", 5*time.Second),
		CDCCommitInterval:     getenvDuration("CDC_COMMIT_INTERVAL", 10*time.Second),
		CDCBatchSize:          getenvInt("CDC_BATCH_SIZE", 500),
		CDCBatchTimeout:       getenvDuration("CDC_BATCH_TIMEOUT", 5*time.Second),
	}
}

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
	res, err := resource.New(ctx, resource.WithAttributes(
		semconv.ServiceName(serviceName),
	))
	if err != nil {
		return nil, err
	}

	exporter, err := buildTraceExporter(ctx, logger)
	if err != nil {
		return nil, err
	}

	tp := sdktrace.NewTracerProvider(
		sdktrace.WithBatcher(exporter),
		sdktrace.WithResource(res),
	)
	otel.SetTracerProvider(tp)
	return tp.Shutdown, nil
}

func buildTraceExporter(ctx context.Context, logger *slog.Logger) (sdktrace.SpanExporter, error) {
	endpoint := getenv("OTEL_EXPORTER_OTLP_ENDPOINT", "")
	if endpoint == "" {
		endpoint = getenv("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", "")
	}

	if endpoint == "" {
		logger.Info("OTLP endpoint not set; using stdout exporter")
		return stdouttrace.New(stdouttrace.WithPrettyPrint())
	}

	secure := false
	if parsed, err := url.Parse(endpoint); err == nil && parsed.Host != "" {
		endpoint = parsed.Host
		secure = strings.EqualFold(parsed.Scheme, "https")
	} else if strings.HasPrefix(endpoint, "https://") {
		secure = true
	}

	endpoint = strings.TrimPrefix(strings.TrimPrefix(endpoint, "http://"), "https://")
	options := []otlptracegrpc.Option{otlptracegrpc.WithEndpoint(endpoint)}
	if !secure {
		options = append(options, otlptracegrpc.WithInsecure())
	}

	return otlptracegrpc.New(ctx, options...)
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

func (m *Metrics) RecordMismatch() {
	m.mismatches.Inc()
}

func (m *Metrics) RecordFixed() {
	m.fixed.Inc()
}

func (m *Metrics) RecordManualReview() {
	m.manualReview.Inc()
}

func NewPSPSource(path string, logger *slog.Logger) *PSPSource {
	return &PSPSource{path: path, logger: logger}
}

func (s *PSPSource) Load(ctx context.Context) ([]Transaction, error) {
	if s.path == "" {
		s.logger.Warn("PSP_EXPORT_PATH not set; using sample PSP export")
		return samplePSPTransactions(), nil
	}

	var transactions []Transaction
	if err := readJSONFile(s.path, &transactions); err != nil {
		return nil, err
	}
	return transactions, nil
}

func NewLedgerStore(cfg Config, logger *slog.Logger) (*LedgerStore, error) {
	store := &LedgerStore{
		entries:    make(map[string]LedgerEntry),
		outputPath: cfg.LedgerOutputPath,
		logger:     logger,
	}

	if cfg.LedgerPath == "" {
		logger.Warn("LEDGER_PATH not set; using sample ledger")
		for _, entry := range sampleLedgerEntries() {
			store.entries[entry.ID] = entry
		}
		return store, nil
	}

	var entries []LedgerEntry
	if err := readJSONFile(cfg.LedgerPath, &entries); err != nil {
		return store, err
	}
	for _, entry := range entries {
		store.entries[entry.ID] = entry
	}
	return store, nil
}

func (s *LedgerStore) Load(ctx context.Context) ([]LedgerEntry, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	entries := make([]LedgerEntry, 0, len(s.entries))
	for _, entry := range s.entries {
		entries = append(entries, entry)
	}
	return entries, nil
}

func (s *LedgerStore) Upsert(entry LedgerEntry) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.entries[entry.ID] = entry
}

func (s *LedgerStore) Persist(ctx context.Context) error {
	if s.outputPath == "" {
		return nil
	}

	s.mu.Lock()
	entries := make([]LedgerEntry, 0, len(s.entries))
	for _, entry := range s.entries {
		entries = append(entries, entry)
	}
	s.mu.Unlock()

	payload, err := json.MarshalIndent(entries, "", "  ")
	if err != nil {
		return err
	}

	dir := filepath.Dir(s.outputPath)
	if dir != "." {
		if err := os.MkdirAll(dir, 0o755); err != nil {
			return err
		}
	}
	return os.WriteFile(s.outputPath, payload, 0o644)
}

func NewFixRegistry(path string, logger *slog.Logger) (*FixRegistry, error) {
	registry := &FixRegistry{
		records: make(map[string]FixRecord),
		path:    path,
		logger:  logger,
	}

	if path == "" {
		return registry, nil
	}

	var records []FixRecord
	if err := readJSONFile(path, &records); err != nil {
		return registry, err
	}
	for _, record := range records {
		registry.records[record.ID] = record
	}
	return registry, nil
}

func (r *FixRegistry) Exists(id string) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	_, ok := r.records[id]
	return ok
}

func (r *FixRegistry) Record(record FixRecord) error {
	r.mu.Lock()
	defer r.mu.Unlock()

	if _, exists := r.records[record.ID]; exists {
		return nil
	}
	r.records[record.ID] = record
	return r.persistLocked()
}

func (r *FixRegistry) persistLocked() error {
	if r.path == "" {
		return nil
	}

	records := make([]FixRecord, 0, len(r.records))
	for _, record := range r.records {
		records = append(records, record)
	}

	payload, err := json.MarshalIndent(records, "", "  ")
	if err != nil {
		return err
	}

	dir := filepath.Dir(r.path)
	if dir != "." {
		if err := os.MkdirAll(dir, 0o755); err != nil {
			return err
		}
	}
	return os.WriteFile(r.path, payload, 0o644)
}

func NewKafkaPublisher(cfg Config, logger *slog.Logger) *KafkaPublisher {
	if len(cfg.KafkaBrokers) == 0 {
		logger.Warn("Kafka not configured; events will not be published")
		return &KafkaPublisher{enabled: false, logger: logger}
	}

	writer := &kafka.Writer{
		Addr:         kafka.TCP(cfg.KafkaBrokers...),
		Topic:        cfg.KafkaTopic,
		Balancer:     &kafka.LeastBytes{},
		RequiredAcks: kafka.RequireAll,
		Async:        false,
	}

	logger.Info("Kafka publisher configured", "brokers", cfg.KafkaBrokers, "topic", cfg.KafkaTopic)
	return &KafkaPublisher{enabled: true, writer: writer, logger: logger}
}

func (p *KafkaPublisher) Publish(ctx context.Context, event ReconciliationEvent) error {
	if !p.enabled {
		return nil
	}

	payload, err := json.Marshal(event)
	if err != nil {
		return err
	}

	key := event.EventID
	if event.TransactionID != "" {
		key = event.TransactionID
	}

	publishCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()

	err = p.writer.WriteMessages(publishCtx, kafka.Message{
		Key:   []byte(key),
		Value: payload,
		Time:  event.OccurredAt,
	})
	if err != nil {
		p.logger.Error("kafka publish failed", "error", err, "event_type", event.EventType)
	}
	return err
}

func (p *KafkaPublisher) Close() error {
	if !p.enabled {
		return nil
	}
	return p.writer.Close()
}

func (r *Reconciler) Run(ctx context.Context) {
	if !r.running.CompareAndSwap(false, true) {
		r.logger.Warn("reconciliation already running; skipping")
		return
	}
	defer r.running.Store(false)

	runID := newID("run")
	runCtx, cancel := context.WithTimeout(ctx, r.timeout)
	defer cancel()

	runCtx, span := r.tracer.Start(runCtx, "reconciliation.run",
		trace.WithAttributes(attribute.String("run_id", runID)))
	defer span.End()

	logger := loggerWithContext(runCtx, r.logger).With("run_id", runID)
	logger.Info("reconciliation started")

	pspTransactions, err := r.pspSource.Load(runCtx)
	if err != nil {
		span.RecordError(err)
		logger.Error("failed to load PSP export", "error", err)
		return
	}

	ledgerEntries, err := r.ledgerStore.Load(runCtx)
	if err != nil {
		span.RecordError(err)
		logger.Error("failed to load ledger", "error", err)
		return
	}

	mismatches := findMismatches(pspTransactions, ledgerEntries)
	logger.Info("reconciliation inputs loaded", "psp_count", len(pspTransactions), "ledger_count", len(ledgerEntries), "mismatches", len(mismatches))

	counts := ReconciliationCounts{}
	ledgerUpdated := false
	for _, mismatch := range mismatches {
		counts.Mismatches++
		r.metrics.RecordMismatch()

		_ = r.publisher.Publish(runCtx, newMismatchEvent(runID, mismatch))

		if mismatch.Fixable {
			applied, err := r.applyFix(runCtx, mismatch)
			if err != nil {
				span.RecordError(err)
				logger.Error("fix failed", "error", err, "transaction_id", mismatch.TransactionID, "mismatch_type", mismatch.Type)
				continue
			}
			if applied {
				counts.Fixed++
				ledgerUpdated = true
				r.metrics.RecordFixed()
				_ = r.publisher.Publish(runCtx, newFixEvent(runID, mismatch))
			}
		} else {
			counts.ManualReview++
			r.metrics.RecordManualReview()
			_ = r.publisher.Publish(runCtx, newManualReviewEvent(runID, mismatch))
		}
	}

	if ledgerUpdated {
		if err := r.ledgerStore.Persist(runCtx); err != nil {
			span.RecordError(err)
			logger.Error("failed to persist ledger updates", "error", err)
		}
	}

	span.SetAttributes(
		attribute.Int("reconciliation.mismatches", counts.Mismatches),
		attribute.Int("reconciliation.fixed", counts.Fixed),
		attribute.Int("reconciliation.manual_review", counts.ManualReview),
	)

	_ = r.publisher.Publish(runCtx, newSummaryEvent(runID, counts))
	logger.Info("reconciliation completed", "mismatches", counts.Mismatches, "fixed", counts.Fixed, "manual_review", counts.ManualReview)
}

func (r *Reconciler) applyFix(ctx context.Context, mismatch Mismatch) (bool, error) {
	fixID := mismatch.FixID()
	if r.fixRegistry.Exists(fixID) {
		r.logger.Info("fix already applied", "transaction_id", mismatch.TransactionID, "mismatch_type", mismatch.Type)
		return false, nil
	}

	fixCtx, span := r.tracer.Start(ctx, "reconciliation.fix",
		trace.WithAttributes(
			attribute.String("transaction_id", mismatch.TransactionID),
			attribute.String("mismatch_type", mismatch.Type),
		))
	defer span.End()

	now := time.Now().UTC()
	switch mismatch.Type {
	case "missing_ledger_entry":
		if mismatch.PSP == nil {
			return false, errors.New("missing PSP data")
		}
		r.ledgerStore.Upsert(LedgerEntry{
			ID:          mismatch.PSP.ID,
			AmountCents: mismatch.PSP.AmountCents,
			Currency:    mismatch.PSP.Currency,
			Type:        "payment",
			Source:      "psp_export",
			UpdatedAt:   now,
		})
	case "amount_mismatch":
		if mismatch.PSP == nil || mismatch.Ledger == nil {
			return false, errors.New("missing ledger data")
		}
		entry := *mismatch.Ledger
		entry.AmountCents = mismatch.PSP.AmountCents
		entry.Source = "reconciliation_adjustment"
		entry.UpdatedAt = now
		r.ledgerStore.Upsert(entry)
	default:
		return false, nil
	}

	if err := r.fixRegistry.Record(FixRecord{
		ID:            fixID,
		AppliedAt:     now,
		MismatchType:  mismatch.Type,
		TransactionID: mismatch.TransactionID,
	}); err != nil {
		r.logger.Warn("failed to persist fix record", "error", err, "transaction_id", mismatch.TransactionID)
	}

	loggerWithContext(fixCtx, r.logger).Info("fix applied", "transaction_id", mismatch.TransactionID, "mismatch_type", mismatch.Type)
	return true, nil
}

func StartScheduler(ctx context.Context, schedule string, runOnStart bool, logger *slog.Logger, run func(context.Context)) (*Scheduler, error) {
	schedule = strings.TrimSpace(schedule)
	if schedule == "" {
		schedule = defaultSchedule
	}

	scheduleCtx, cancel := context.WithCancel(ctx)
	var wg sync.WaitGroup

	trigger := func() {
		wg.Add(1)
		go func() {
			defer wg.Done()
			run(scheduleCtx)
		}()
	}

	if runOnStart {
		trigger()
	}

	if interval, err := time.ParseDuration(schedule); err == nil {
		ticker := time.NewTicker(interval)
		wg.Add(1)
		go func() {
			defer wg.Done()
			for {
				select {
				case <-scheduleCtx.Done():
					return
				case <-ticker.C:
					trigger()
				}
			}
		}()

		stop := func(stopCtx context.Context) error {
			cancel()
			ticker.Stop()
			return waitForShutdown(stopCtx, &wg)
		}
		return &Scheduler{stop: stop}, nil
	}

	parser := cron.NewParser(cron.Minute | cron.Hour | cron.Dom | cron.Month | cron.Dow | cron.Descriptor)
	cronScheduler := cron.New(cron.WithParser(parser))
	if _, err := cronScheduler.AddFunc(schedule, trigger); err != nil {
		cancel()
		return nil, err
	}
	cronScheduler.Start()

	stop := func(stopCtx context.Context) error {
		cancel()
		cronScheduler.Stop()
		return waitForShutdown(stopCtx, &wg)
	}
	return &Scheduler{stop: stop}, nil
}

func (s *Scheduler) Stop(ctx context.Context) error {
	if s == nil || s.stop == nil {
		return nil
	}
	return s.stop(ctx)
}

func waitForShutdown(ctx context.Context, wg *sync.WaitGroup) error {
	done := make(chan struct{})
	go func() {
		wg.Wait()
		close(done)
	}()

	select {
	case <-done:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

func findMismatches(psp []Transaction, ledger []LedgerEntry) []Mismatch {
	ledgerByID := make(map[string]LedgerEntry, len(ledger))
	for _, entry := range ledger {
		ledgerByID[entry.ID] = entry
	}

	pspByID := make(map[string]Transaction, len(psp))
	mismatches := make([]Mismatch, 0)
	for _, txn := range psp {
		pspByID[txn.ID] = txn
		entry, ok := ledgerByID[txn.ID]
		if !ok {
			copyTxn := txn
			mismatches = append(mismatches, Mismatch{
				TransactionID: txn.ID,
				Type:          "missing_ledger_entry",
				Reason:        "ledger entry missing",
				Fixable:       true,
				PSP:           &copyTxn,
			})
			continue
		}

		if entry.Currency != txn.Currency {
			copyTxn := txn
			copyEntry := entry
			mismatches = append(mismatches, Mismatch{
				TransactionID: txn.ID,
				Type:          "currency_mismatch",
				Reason:        "currency mismatch",
				Fixable:       false,
				PSP:           &copyTxn,
				Ledger:        &copyEntry,
			})
			continue
		}

		if entry.AmountCents != txn.AmountCents {
			copyTxn := txn
			copyEntry := entry
			mismatches = append(mismatches, Mismatch{
				TransactionID: txn.ID,
				Type:          "amount_mismatch",
				Reason:        "amount mismatch",
				Fixable:       true,
				PSP:           &copyTxn,
				Ledger:        &copyEntry,
			})
		}
	}

	for _, entry := range ledger {
		if _, ok := pspByID[entry.ID]; ok {
			continue
		}
		copyEntry := entry
		mismatches = append(mismatches, Mismatch{
			TransactionID: entry.ID,
			Type:          "missing_psp_export",
			Reason:        "missing PSP export record",
			Fixable:       false,
			Ledger:        &copyEntry,
		})
	}

	return mismatches
}

func (m Mismatch) FixID() string {
	return fmt.Sprintf("%s:%s", m.Type, m.TransactionID)
}

func newMismatchEvent(runID string, mismatch Mismatch) ReconciliationEvent {
	return ReconciliationEvent{
		EventID:       newID("event"),
		RunID:         runID,
		EventType:     "mismatch",
		OccurredAt:    time.Now().UTC(),
		TransactionID: mismatch.TransactionID,
		MismatchType:  mismatch.Type,
		Reason:        mismatch.Reason,
	}
}

func newFixEvent(runID string, mismatch Mismatch) ReconciliationEvent {
	return ReconciliationEvent{
		EventID:       newID("event"),
		RunID:         runID,
		EventType:     "fixed",
		OccurredAt:    time.Now().UTC(),
		TransactionID: mismatch.TransactionID,
		MismatchType:  mismatch.Type,
		Reason:        mismatch.Reason,
		FixApplied:    true,
	}
}

func newManualReviewEvent(runID string, mismatch Mismatch) ReconciliationEvent {
	return ReconciliationEvent{
		EventID:       newID("event"),
		RunID:         runID,
		EventType:     "manual_review",
		OccurredAt:    time.Now().UTC(),
		TransactionID: mismatch.TransactionID,
		MismatchType:  mismatch.Type,
		Reason:        mismatch.Reason,
	}
}

func newSummaryEvent(runID string, counts ReconciliationCounts) ReconciliationEvent {
	return ReconciliationEvent{
		EventID:    newID("event"),
		RunID:      runID,
		EventType:  "summary",
		OccurredAt: time.Now().UTC(),
		Counts:     &counts,
	}
}

func handleHealth(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		w.Header().Set("Allow", "GET, HEAD")
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

func handleReady(ready *atomic.Bool) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet && r.Method != http.MethodHead {
			w.Header().Set("Allow", "GET, HEAD")
			writeError(w, http.StatusMethodNotAllowed, "method not allowed")
			return
		}
		if !ready.Load() {
			writeJSON(w, http.StatusServiceUnavailable, map[string]string{"status": "not_ready"})
			return
		}
		writeJSON(w, http.StatusOK, map[string]string{"status": "ready"})
	}
}

func writeJSON(w http.ResponseWriter, status int, payload any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	encoder := json.NewEncoder(w)
	encoder.SetEscapeHTML(false)
	_ = encoder.Encode(payload)
}

func writeError(w http.ResponseWriter, status int, message string) {
	writeJSON(w, status, errorResponse{Error: message})
}

func loggerWithContext(ctx context.Context, logger *slog.Logger) *slog.Logger {
	span := trace.SpanContextFromContext(ctx)
	if !span.IsValid() {
		return logger
	}
	return logger.With(
		"trace_id", span.TraceID().String(),
		"span_id", span.SpanID().String(),
	)
}

func readJSONFile(path string, out any) error {
	payload, err := os.ReadFile(path)
	if err != nil {
		return err
	}
	if len(strings.TrimSpace(string(payload))) == 0 {
		return nil
	}
	return json.Unmarshal(payload, out)
}

func newID(prefix string) string {
	random := make([]byte, 6)
	if _, err := rand.Read(random); err != nil {
		return fmt.Sprintf("%s-%d", prefix, time.Now().UnixNano())
	}
	return fmt.Sprintf("%s-%d-%s", prefix, time.Now().UnixNano(), hex.EncodeToString(random))
}

func getenv(key, fallback string) string {
	if value := strings.TrimSpace(os.Getenv(key)); value != "" {
		return value
	}
	return fallback
}

func getenvDuration(key string, fallback time.Duration) time.Duration {
	value := strings.TrimSpace(os.Getenv(key))
	if value == "" {
		return fallback
	}
	parsed, err := time.ParseDuration(value)
	if err != nil {
		return fallback
	}
	return parsed
}

func getenvBool(key string, fallback bool) bool {
	value := strings.TrimSpace(os.Getenv(key))
	if value == "" {
		return fallback
	}
	return strings.EqualFold(value, "true") || value == "1" || strings.EqualFold(value, "yes")
}

func getenvInt(key string, fallback int) int {
	value := strings.TrimSpace(os.Getenv(key))
	if value == "" {
		return fallback
	}
	parsed, err := strconv.Atoi(value)
	if err != nil {
		return fallback
	}
	return parsed
}

func splitCSV(value string) []string {
	if strings.TrimSpace(value) == "" {
		return nil
	}
	parts := strings.FieldsFunc(value, func(r rune) bool {
		return r == ',' || r == ';' || r == ' '
	})
	out := make([]string, 0, len(parts))
	for _, part := range parts {
		trimmed := strings.TrimSpace(part)
		if trimmed != "" {
			out = append(out, trimmed)
		}
	}
	return out
}

func samplePSPTransactions() []Transaction {
	return []Transaction{
		{ID: "psp-1001", AmountCents: 1050, Currency: "USD", Status: "captured"},
		{ID: "psp-1002", AmountCents: 500, Currency: "USD", Status: "captured"},
	}
}

func sampleLedgerEntries() []LedgerEntry {
	return []LedgerEntry{
		{ID: "psp-1001", AmountCents: 950, Currency: "USD", Type: "payment", Source: "ledger_seed", UpdatedAt: time.Now().UTC().Add(-24 * time.Hour)},
	}
}
