package reconciliation

import (
	"context"
	"database/sql"
	"errors"
	"log/slog"
	"sync/atomic"
	"time"

	"github.com/robfig/cron/v3"
)

const defaultAdvisoryLockID int64 = 7283910451

type DailyScheduler struct {
	reconciler *DBReconciler
	db         *sql.DB
	lockID     int64
	logger     *slog.Logger
	schedule   string
	running    atomic.Bool
	stop       func(context.Context) error
}

func NewDailyScheduler(reconciler *DBReconciler, logger *slog.Logger, schedule string) *DailyScheduler {
	if logger == nil {
		logger = slog.Default()
	}
	var db *sql.DB
	if reconciler != nil {
		db = reconciler.db
	}
	return &DailyScheduler{
		reconciler: reconciler,
		db:         db,
		lockID:     defaultAdvisoryLockID,
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
	logger := s.getLogger()
	yesterday := time.Now().UTC().Add(-24 * time.Hour)
	result, err := s.executeWithLock(ctx, yesterday)
	if err != nil {
		if errors.Is(err, ErrReconciliationAlreadyRunning) {
			logger.Warn("reconciliation already running; skipping scheduled run")
			return
		}
		logger.Error("scheduled reconciliation failed", "error", err, "run_date", yesterday.Format("2006-01-02"))
		return
	}

	logger.Info("scheduled reconciliation completed",
		"run_id", result.RunID,
		"mismatches", result.MismatchCount,
		"auto_fixed", result.AutoFixedCount,
		"manual_review", result.ManualReviewCount,
		"duration_ms", result.Duration.Milliseconds(),
	)
}

// RunOnce executes reconciliation immediately for the given date.
func (s *DailyScheduler) RunOnce(ctx context.Context, runDate time.Time) (*ReconciliationResult, error) {
	result, err := s.executeWithLock(ctx, runDate)
	if errors.Is(err, ErrReconciliationAlreadyRunning) {
		s.getLogger().Warn("reconciliation already running; rejecting trigger")
	}
	return result, err
}

func (s *DailyScheduler) executeWithLock(ctx context.Context, runDate time.Time) (*ReconciliationResult, error) {
	if !s.running.CompareAndSwap(false, true) {
		return nil, ErrReconciliationAlreadyRunning
	}
	defer s.running.Store(false)

	reconcileCtx, cancel := context.WithTimeout(ctx, 2*time.Minute)
	defer cancel()

	if db := s.dbHandle(); db != nil {
		release, err := acquireSessionAdvisoryLock(reconcileCtx, db, s.advisoryLockID(), s.getLogger())
		if err != nil {
			return nil, err
		}
		defer release()
	}

	if s.reconciler == nil {
		return nil, errors.New("reconciler is not configured")
	}
	return s.reconciler.ReconcileDailyRun(reconcileCtx, runDate)
}

// acquireSessionAdvisoryLock pins a database connection for the duration of the run
// because PostgreSQL advisory locks are session-scoped.
func acquireSessionAdvisoryLock(ctx context.Context, db *sql.DB, lockID int64, logger *slog.Logger) (func(), error) {
	conn, err := db.Conn(ctx)
	if err != nil {
		return nil, err
	}

	var acquired bool
	if err := conn.QueryRowContext(ctx, "SELECT pg_try_advisory_lock($1)", lockID).Scan(&acquired); err != nil {
		_ = conn.Close()
		return nil, err
	}
	if !acquired {
		_ = conn.Close()
		return nil, ErrReconciliationAlreadyRunning
	}

	return func() {
		unlockCtx, unlockCancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer unlockCancel()
		var unlocked bool
		if err := conn.QueryRowContext(unlockCtx, "SELECT pg_advisory_unlock($1)", lockID).Scan(&unlocked); err != nil {
			logger.Warn("failed to release advisory lock", "error", err, "lock_id", lockID)
		}
		_ = conn.Close()
	}, nil
}

func (s *DailyScheduler) dbHandle() *sql.DB {
	if s.db != nil {
		return s.db
	}
	if s.reconciler != nil {
		return s.reconciler.db
	}
	return nil
}

func (s *DailyScheduler) advisoryLockID() int64 {
	if s.lockID != 0 {
		return s.lockID
	}
	return defaultAdvisoryLockID
}

func (s *DailyScheduler) getLogger() *slog.Logger {
	if s.logger != nil {
		return s.logger
	}
	return slog.Default()
}
