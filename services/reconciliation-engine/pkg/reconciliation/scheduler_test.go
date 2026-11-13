package reconciliation

import (
	"context"
	"database/sql/driver"
	"errors"
	"io"
	"log/slog"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestDailyScheduler_AdvisoryLockPinnedToSingleConnection(t *testing.T) {
	var mu sync.Mutex
	var lockConnID, unlockConnID int64
	locked := false

	queryFn := func(connID int64, query string, args []driver.NamedValue) (driver.Rows, error) {
		switch {
		case strings.Contains(query, "pg_try_advisory_lock"):
			mu.Lock()
			defer mu.Unlock()
			lockConnID = connID
			if locked {
				return &stubRows{columns: []string{"pg_try_advisory_lock"}, data: [][]driver.Value{{false}}}, nil
			}
			locked = true
			return &stubRows{columns: []string{"pg_try_advisory_lock"}, data: [][]driver.Value{{true}}}, nil
		case strings.Contains(query, "pg_advisory_unlock"):
			mu.Lock()
			defer mu.Unlock()
			unlockConnID = connID
			locked = false
			return &stubRows{columns: []string{"pg_advisory_unlock"}, data: [][]driver.Value{{true}}}, nil
		case strings.Contains(query, "SELECT run_id FROM reconciliation_runs"):
			return &stubRows{columns: []string{"run_id"}}, nil
		case strings.Contains(query, "INSERT INTO reconciliation_runs"):
			return &stubRows{columns: []string{"run_id"}, data: [][]driver.Value{{int64(11)}}}, nil
		case strings.Contains(query, "FROM payment_ledger"):
			return &stubRows{columns: []string{"transaction_id", "amount_cents"}}, nil
		case strings.Contains(query, "FROM psp_export_snapshot"):
			return &stubRows{columns: []string{"transaction_id", "amount_cents"}}, nil
		default:
			return &stubRows{}, nil
		}
	}

	db, _ := newStubDB(queryFn, nil)
	defer db.Close()

	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	reconciler := NewDBReconciler(db, logger, nil, nil)
	scheduler := NewDailyScheduler(reconciler, logger, "0 2 * * *")

	res, err := scheduler.RunOnce(context.Background(), time.Date(2026, 11, 13, 0, 0, 0, 0, time.UTC))
	require.NoError(t, err)
	require.NotNil(t, res)
	assert.Equal(t, int64(11), res.RunID)

	mu.Lock()
	defer mu.Unlock()
	assert.NotZero(t, lockConnID, "expected pg_try_advisory_lock to be called")
	assert.Equal(t, lockConnID, unlockConnID, "pg_advisory_unlock must run on the exact same session connection")
	assert.False(t, locked, "advisory lock must be released after RunOnce completes")
}

func TestDailyScheduler_AdvisoryLockContentionSkipsAndRejects(t *testing.T) {
	queryFn := func(_ int64, query string, _ []driver.NamedValue) (driver.Rows, error) {
		if strings.Contains(query, "pg_try_advisory_lock") {
			return &stubRows{columns: []string{"pg_try_advisory_lock"}, data: [][]driver.Value{{false}}}, nil
		}
		return &stubRows{}, nil
	}

	db, state := newStubDB(queryFn, nil)
	defer db.Close()

	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	reconciler := NewDBReconciler(db, logger, nil, nil)
	scheduler := NewDailyScheduler(reconciler, logger, "0 2 * * *")

	res, err := scheduler.RunOnce(context.Background(), time.Date(2026, 11, 13, 0, 0, 0, 0, time.UTC))
	assert.ErrorIs(t, err, ErrReconciliationAlreadyRunning)
	assert.Nil(t, res)

	scheduler.runReconciliation(context.Background())

	for _, stmt := range state.recorded() {
		assert.NotContains(t, stmt.Query, "reconciliation_runs", "must not query reconciliation_runs when advisory lock is held elsewhere")
		assert.NotContains(t, stmt.Query, "pg_advisory_unlock", "must not unlock an advisory lock that was not acquired")
	}
}

func TestDailyScheduler_AdvisoryLockErrorAbortsRun(t *testing.T) {
	lockErr := errors.New("db connection lost")
	queryFn := func(_ int64, query string, _ []driver.NamedValue) (driver.Rows, error) {
		if strings.Contains(query, "pg_try_advisory_lock") {
			return nil, lockErr
		}
		return &stubRows{}, nil
	}

	db, state := newStubDB(queryFn, nil)
	defer db.Close()

	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	reconciler := NewDBReconciler(db, logger, nil, nil)
	scheduler := NewDailyScheduler(reconciler, logger, "0 2 * * *")

	res, err := scheduler.RunOnce(context.Background(), time.Date(2026, 11, 13, 0, 0, 0, 0, time.UTC))
	require.Error(t, err)
	assert.Nil(t, res)

	scheduler.runReconciliation(context.Background())

	for _, stmt := range state.recorded() {
		assert.NotContains(t, stmt.Query, "reconciliation_runs")
	}
}

func TestDailyScheduler_UnlockRunsWhenParentContextCanceled(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	var mu sync.Mutex
	var unlocked bool
	var unlockErr error

	queryFn := func(_ int64, query string, _ []driver.NamedValue) (driver.Rows, error) {
		switch {
		case strings.Contains(query, "pg_try_advisory_lock"):
			return &stubRows{columns: []string{"pg_try_advisory_lock"}, data: [][]driver.Value{{true}}}, nil
		case strings.Contains(query, "pg_advisory_unlock"):
			mu.Lock()
			unlocked = true
			mu.Unlock()
			return &stubRows{columns: []string{"pg_advisory_unlock"}, data: [][]driver.Value{{true}}}, nil
		case strings.Contains(query, "SELECT run_id FROM reconciliation_runs"):
			cancel()
			return nil, ctx.Err()
		default:
			return &stubRows{}, nil
		}
	}

	db, _ := newStubDB(queryFn, nil)
	defer db.Close()

	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	reconciler := NewDBReconciler(db, logger, nil, nil)
	scheduler := NewDailyScheduler(reconciler, logger, "0 2 * * *")

	_, unlockErr = scheduler.RunOnce(ctx, time.Date(2026, 11, 13, 0, 0, 0, 0, time.UTC))
	require.Error(t, unlockErr)

	mu.Lock()
	defer mu.Unlock()
	assert.True(t, unlocked, "pg_advisory_unlock must still execute even if the run context was canceled")
}

func TestDailyScheduler_InMemoryFallbackWhenDBNil(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	scheduler := NewDailyScheduler(nil, logger, "0 2 * * *")
	scheduler.running.Store(true)

	_, err := scheduler.RunOnce(context.Background(), time.Now())
	assert.ErrorIs(t, err, ErrReconciliationAlreadyRunning)
}
