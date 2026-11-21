package reconciliation

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"log/slog"
	"time"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/trace"
)

func (r *DBReconciler) GetReconciliationRuns(ctx context.Context, limit int, offset int) ([]ReconciliationRun, error) {
	rows, err := r.db.QueryContext(ctx,
		`SELECT run_id, run_date, scheduled_at, started_at, completed_at,
			mismatch_count, auto_fixed_count, manual_review_count, status, created_at
		FROM reconciliation_runs
		ORDER BY run_date DESC, run_id DESC
		LIMIT $1 OFFSET $2`,
		limit, offset)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var runs []ReconciliationRun
	for rows.Next() {
		var run ReconciliationRun
		if err := rows.Scan(&run.RunID, &run.RunDate, &run.ScheduledAt, &run.StartedAt,
			&run.CompletedAt, &run.MismatchCount, &run.AutoFixedCount,
			&run.ManualReviewCount, &run.Status, &run.CreatedAt); err != nil {
			return nil, err
		}
		runs = append(runs, run)
	}
	return runs, rows.Err()
}

func (r *DBReconciler) GetMismatchesForRun(ctx context.Context, runID int64) ([]ReconciliationMismatch, error) {
	rows, err := r.db.QueryContext(ctx,
		`SELECT mismatch_id, run_id, transaction_id, ledger_amount, psp_amount,
			discrepancy_amount, discrepancy_reason, auto_fixed, manual_review_required, fix_applied_at, created_at
		FROM reconciliation_mismatches
		WHERE run_id = $1
		ORDER BY created_at DESC, mismatch_id ASC`,
		runID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	return scanAndClassifyMismatches(rows, time.Now().UTC())
}

func (r *DBReconciler) GetPendingMismatches(ctx context.Context, limit, offset int) ([]ReconciliationMismatch, error) {
	if limit <= 0 || limit > 1000 {
		limit = 50
	}
	if offset < 0 {
		offset = 0
	}
	rows, err := r.db.QueryContext(ctx,
		`SELECT mismatch_id, run_id, transaction_id, ledger_amount, psp_amount,
			discrepancy_amount, discrepancy_reason, auto_fixed, manual_review_required, fix_applied_at, created_at
		FROM reconciliation_mismatches
		WHERE manual_review_required = true AND fix_applied_at IS NULL
		ORDER BY created_at ASC, mismatch_id ASC
		LIMIT $1 OFFSET $2`,
		limit, offset)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	mismatches, err := scanAndClassifyMismatches(rows, time.Now().UTC())
	if err != nil {
		return nil, err
	}
	SortReconciliationMismatches(mismatches)
	return mismatches, nil
}

func scanAndClassifyMismatches(rows *sql.Rows, now time.Time) ([]ReconciliationMismatch, error) {
	var mismatches []ReconciliationMismatch
	for rows.Next() {
		var m ReconciliationMismatch
		if err := rows.Scan(&m.MismatchID, &m.RunID, &m.TransactionID, &m.LedgerAmount, &m.PSPAmount,
			&m.DiscrepancyAmount, &m.DiscrepancyReason, &m.AutoFixed, &m.ManualReviewRequired, &m.FixAppliedAt, &m.CreatedAt); err != nil {
			return nil, err
		}
		var age time.Duration
		if !m.CreatedAt.IsZero() && now.After(m.CreatedAt) {
			age = now.Sub(m.CreatedAt)
		}
		diffCents := calculateDiscrepancyCents(m.LedgerAmount, m.PSPAmount)
		m.AgeHours = age.Hours()
		m.AgeBucket = ClassifyAgeBucket(age)
		m.Severity = ClassifySeverity(diffCents, m.DiscrepancyReason, age)
		mismatches = append(mismatches, m)
	}
	return mismatches, rows.Err()
}

func (r *DBReconciler) ReviewMismatch(ctx context.Context, mismatchID int64, manualReviewRequired bool) error {
	_, err := r.db.ExecContext(ctx,
		`UPDATE reconciliation_mismatches
		SET manual_review_required = $1
		WHERE mismatch_id = $2`,
		manualReviewRequired, mismatchID)
	return err
}

func (r *DBReconciler) ApplyFix(ctx context.Context, mismatchID int64, fixAction string, operatorID, notes *string) error {
	tracer := r.tracer
	if tracer == nil {
		tracer = otel.Tracer("reconciliation")
	}
	logger := r.logger
	if logger == nil {
		logger = slog.Default()
	}

	ctx, span := tracer.Start(ctx, "reconciliation.apply_fix",
		trace.WithAttributes(attribute.Int64("mismatch_id", mismatchID)))
	defer span.End()

	tx, err := r.db.BeginTx(ctx, nil)
	if err != nil {
		span.RecordError(err)
		return fmt.Errorf("failed to begin transaction: %w", err)
	}
	defer func() { _ = tx.Rollback() }()

	var runID int64
	var txnID string
	var pspAmountStr string
	err = tx.QueryRowContext(ctx,
		`SELECT run_id, transaction_id, COALESCE(psp_amount::text, '0.00')
		FROM reconciliation_mismatches
		WHERE mismatch_id = $1 AND fix_applied_at IS NULL
		FOR UPDATE`,
		mismatchID).Scan(&runID, &txnID, &pspAmountStr)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			var existing int
			if fixCheckErr := tx.QueryRowContext(ctx, `SELECT 1 FROM reconciliation_fixes WHERE mismatch_id = $1 LIMIT 1`, mismatchID).Scan(&existing); fixCheckErr == nil && existing == 1 {
				logger.Info("fix already applied, returning idempotently", "mismatch_id", mismatchID)
				return nil
			}
			if fixedCheckErr := tx.QueryRowContext(ctx, `SELECT 1 FROM reconciliation_mismatches WHERE mismatch_id = $1 AND fix_applied_at IS NOT NULL LIMIT 1`, mismatchID).Scan(&existing); fixedCheckErr == nil && existing == 1 {
				logger.Info("mismatch already marked fixed, returning idempotently", "mismatch_id", mismatchID)
				return nil
			}
		}
		span.RecordError(err)
		return fmt.Errorf("failed to get mismatch: %w", err)
	}

	if _, err := tx.ExecContext(ctx,
		`INSERT INTO reconciliation_fixes (mismatch_id, fix_applied_at, fix_action, operator_id, notes, created_at)
		VALUES ($1, NOW(), $2, $3, $4, NOW())`,
		mismatchID, fixAction, operatorID, notes); err != nil {
		span.RecordError(err)
		return fmt.Errorf("failed to insert fix record: %w", err)
	}

	adjustedCents := parseAmountCents(pspAmountStr)
	if _, err := tx.ExecContext(ctx,
		`INSERT INTO payment_ledger (transaction_id, payment_id, amount_cents, currency, status, created_at, updated_at)
		VALUES ($1, $1, $2, 'INR', 'ADJUSTED', NOW(), NOW())
		ON CONFLICT (transaction_id) DO UPDATE
		SET amount_cents = EXCLUDED.amount_cents, status = 'ADJUSTED', updated_at = NOW()`,
		txnID, adjustedCents); err != nil {
		span.RecordError(err)
		return fmt.Errorf("failed to write ledger adjustment: %w", err)
	}

	res, err := tx.ExecContext(ctx,
		`UPDATE reconciliation_mismatches
		SET auto_fixed = true, manual_review_required = false, fix_applied_at = NOW()
		WHERE mismatch_id = $1 AND fix_applied_at IS NULL`,
		mismatchID)
	if err != nil {
		span.RecordError(err)
		return fmt.Errorf("failed to update mismatch: %w", err)
	}
	if rowsAffected, rowsErr := res.RowsAffected(); rowsErr == nil && rowsAffected == 0 {
		err := fmt.Errorf("failed to update mismatch %d: no unfixed row matched", mismatchID)
		span.RecordError(err)
		return err
	}

	if err := tx.Commit(); err != nil {
		span.RecordError(err)
		return fmt.Errorf("failed to commit transaction: %w", err)
	}

	logger.Info("fix applied", "mismatch_id", mismatchID, "fix_action", fixAction)
	return nil
}
