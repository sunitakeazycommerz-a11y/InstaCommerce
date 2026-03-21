package reconciliation

import (
	"context"
	"log/slog"
	"sync/atomic"
	"time"

	"github.com/robfig/cron/v3"
)

type DailyScheduler struct {
	reconciler *DBReconciler
	logger     *slog.Logger
	schedule   string
	running    atomic.Bool
	stop       func(context.Context) error
}

func NewDailyScheduler(reconciler *DBReconciler, logger *slog.Logger, schedule string) *DailyScheduler {
	return &DailyScheduler{
		reconciler: reconciler,
		logger:     logger,
		schedule:   schedule,
	}
}

func (s *DailyScheduler) Start(ctx context.Context) error {
	schedule := s.schedule
	if schedule == "" {
		schedule = "0 2 * * *" // 2 AM UTC daily
	}

	parser := cron.NewParser(cron.Minute | cron.Hour | cron.Dom | cron.Month | cron.Dow)
	cronScheduler := cron.New(cron.WithParser(parser))

	if _, err := cronScheduler.AddFunc(schedule, func() {
		s.runReconciliation(ctx)
	}); err != nil {
		return err
	}

	cronScheduler.Start()
	s.logger.Info("daily scheduler started", "schedule", schedule)

	s.stop = func(stopCtx context.Context) error {
		cronScheduler.Stop()
		s.logger.Info("daily scheduler stopped")
		return nil
	}

	return nil
}

func (s *DailyScheduler) Stop(ctx context.Context) error {
	if s.stop != nil {
		return s.stop(ctx)
	}
	return nil
}

func (s *DailyScheduler) runReconciliation(ctx context.Context) {
	if !s.running.CompareAndSwap(false, true) {
		s.logger.Warn("reconciliation already running; skipping scheduled run")
		return
	}
	defer s.running.Store(false)

	// Use 2-minute timeout for reconciliation
	reconcileCtx, cancel := context.WithTimeout(ctx, 2*time.Minute)
	defer cancel()

	yesterday := time.Now().UTC().Add(-24 * time.Hour)
	result, err := s.reconciler.ReconcileDailyRun(reconcileCtx, yesterday)

	if err != nil {
		s.logger.Error("scheduled reconciliation failed", "error", err, "run_date", yesterday.Format("2006-01-02"))
		return
	}

	s.logger.Info("scheduled reconciliation completed",
		"run_id", result.RunID,
		"mismatches", result.MismatchCount,
		"auto_fixed", result.AutoFixedCount,
		"manual_review", result.ManualReviewCount,
		"duration_ms", result.Duration.Milliseconds(),
	)
}

// RunOnce executes reconciliation immediately for the given date
func (s *DailyScheduler) RunOnce(ctx context.Context, runDate time.Time) (*ReconciliationResult, error) {
	if !s.running.CompareAndSwap(false, true) {
		s.logger.Warn("reconciliation already running; rejecting manual trigger")
		return nil, ErrReconciliationAlreadyRunning
	}
	defer s.running.Store(false)

	reconcileCtx, cancel := context.WithTimeout(ctx, 2*time.Minute)
	defer cancel()

	return s.reconciler.ReconcileDailyRun(reconcileCtx, runDate)
}
