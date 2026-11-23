package reconciliation

import (
	"context"
	"database/sql/driver"
	"errors"
	"io"
	"log/slog"
	"strings"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestReconcileDailyRun_DoesNotPublishBeforeCommitAndRollsBackSavepoint(t *testing.T) {
	t.Run("rolls back savepoint on failed mismatch so subsequent mismatches and commit succeed", func(t *testing.T) {
		insertErr := errors.New("db constraint failure on mismatch")
		queryFn := func(_ int64, query string, _ []driver.NamedValue) (driver.Rows, error) {
			switch {
			case strings.Contains(query, "SELECT run_id FROM reconciliation_runs"):
				return &stubRows{columns: []string{"run_id"}}, nil
			case strings.Contains(query, "INSERT INTO reconciliation_runs"):
				return &stubRows{columns: []string{"run_id"}, data: [][]driver.Value{{int64(77)}}}, nil
			case strings.Contains(query, "FROM payment_ledger"):
				return &stubRows{
					columns: []string{"transaction_id", "amount_cents"},
					data: [][]driver.Value{
						{"txn-bad", int64(1000)},
						{"txn-good", int64(2000)},
					},
				}, nil
			case strings.Contains(query, "FROM psp_export_snapshot"):
				return &stubRows{
					columns: []string{"transaction_id", "amount_cents"},
					data: [][]driver.Value{
						{"txn-bad", int64(1500)},
						{"txn-good", int64(2500)},
					},
				}, nil
			default:
				return &stubRows{}, nil
			}
		}
		execFn := func(_ int64, query string, args []driver.NamedValue) (driver.Result, error) {
			if strings.Contains(query, "INSERT INTO reconciliation_mismatches") && len(args) > 1 && args[1].Value == "txn-bad" {
				return nil, insertErr
			}
			return driver.RowsAffected(1), nil
		}

		db, state := newStubDB(queryFn, execFn)
		defer db.Close()

		writer := &stubKafkaWriter{}
		pub := newEventPublisherWithWriter(writer, "reconciliation.events", slog.New(slog.NewTextHandler(io.Discard, nil)))
		r := NewDBReconciler(db, slog.New(slog.NewTextHandler(io.Discard, nil)), nil, pub)

		res, err := r.ReconcileDailyRun(context.Background(), time.Date(2026, 11, 14, 0, 0, 0, 0, time.UTC))
		require.Error(t, err)
		require.NotNil(t, res)
		assert.Equal(t, 1, state.Commits)

		var sawSavepoint, sawRollbackSavepoint, sawReleaseSavepoint bool
		for _, stmt := range state.recorded() {
			if stmt.Query == "SAVEPOINT mismatch_sp" {
				sawSavepoint = true
			}
			if stmt.Query == "ROLLBACK TO SAVEPOINT mismatch_sp" {
				sawRollbackSavepoint = true
			}
			if stmt.Query == "RELEASE SAVEPOINT mismatch_sp" {
				sawReleaseSavepoint = true
			}
		}
		assert.True(t, sawSavepoint)
		assert.True(t, sawRollbackSavepoint)
		assert.True(t, sawReleaseSavepoint)
		// 1 mismatch event + 1 discrepancy alert for txn-good + 1 completion event = 3 post-commit events
		assert.Len(t, writer.messages, 3)
	})

	t.Run("does not publish Kafka events when run counts update fails before commit", func(t *testing.T) {
		updateCountsErr := errors.New("update run counts failed")
		queryFn := func(_ int64, query string, _ []driver.NamedValue) (driver.Rows, error) {
			switch {
			case strings.Contains(query, "SELECT run_id FROM reconciliation_runs"):
				return &stubRows{columns: []string{"run_id"}}, nil
			case strings.Contains(query, "INSERT INTO reconciliation_runs"):
				return &stubRows{columns: []string{"run_id"}, data: [][]driver.Value{{int64(78)}}}, nil
			case strings.Contains(query, "FROM payment_ledger"):
				return &stubRows{
					columns: []string{"transaction_id", "amount_cents"},
					data:    [][]driver.Value{{"txn-1", int64(1000)}},
				}, nil
			case strings.Contains(query, "FROM psp_export_snapshot"):
				return &stubRows{
					columns: []string{"transaction_id", "amount_cents"},
					data:    [][]driver.Value{{"txn-1", int64(1500)}},
				}, nil
			default:
				return &stubRows{}, nil
			}
		}
		execFn := func(_ int64, query string, _ []driver.NamedValue) (driver.Result, error) {
			if strings.Contains(query, "SET mismatch_count = $1") {
				return nil, updateCountsErr
			}
			return driver.RowsAffected(1), nil
		}

		db, state := newStubDB(queryFn, execFn)
		defer db.Close()

		writer := &stubKafkaWriter{}
		pub := newEventPublisherWithWriter(writer, "reconciliation.events", slog.New(slog.NewTextHandler(io.Discard, nil)))
		r := NewDBReconciler(db, slog.New(slog.NewTextHandler(io.Discard, nil)), nil, pub)

		_, err := r.ReconcileDailyRun(context.Background(), time.Date(2026, 11, 15, 0, 0, 0, 0, time.UTC))
		require.ErrorIs(t, err, updateCountsErr)
		assert.Equal(t, 0, state.Commits)
		assert.Empty(t, writer.messages, "must not publish Kafka events when transaction does not commit")
	})
}
