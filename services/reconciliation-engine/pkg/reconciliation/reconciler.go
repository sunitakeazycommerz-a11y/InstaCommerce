package reconciliation

import (
	"context"
	"database/sql"
	"fmt"
	"log/slog"
	"time"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/trace"
)

type DBReconciler struct {
	db     *sql.DB
	logger *slog.Logger
	tracer trace.Tracer
	pubsub *EventPublisher
}

func NewDBReconciler(db *sql.DB, logger *slog.Logger, tracer trace.Tracer, pubsub *EventPublisher) *DBReconciler {
	if logger == nil {
		logger = slog.Default()
	}
	if tracer == nil {
		tracer = otel.Tracer("reconciliation")
	}
	return &DBReconciler{
		db:     db,
		logger: logger,
		tracer: tracer,
		pubsub: pubsub,
	}
}

type ReconciliationResult struct {
	RunID             int64
	MismatchCount     int
	AutoFixedCount    int
	ManualReviewCount int
	Duration          time.Duration
}

func (r *DBReconciler) ReconcileDailyRun(ctx context.Context, runDate time.Time) (*ReconciliationResult, error) {
	dayStart := time.Date(runDate.UTC().Year(), runDate.UTC().Month(), runDate.UTC().Day(), 0, 0, 0, 0, time.UTC)
	dayEnd := dayStart.Add(24 * time.Hour)
	return r.ReconcileRange(ctx, dayStart, dayEnd)
}

func (r *DBReconciler) ReconcileRange(ctx context.Context, start, end time.Time) (*ReconciliationResult, error) {
	tracer := r.tracer
	if tracer == nil {
		tracer = otel.Tracer("reconciliation")
	}
	logger := r.logger
	if logger == nil {
		logger = slog.Default()
	}

	runDate := start.UTC()
	if !end.After(start) {
		end = start.Add(24 * time.Hour)
	}

	ctx, span := tracer.Start(ctx, "reconciliation.daily_run",
		trace.WithAttributes(attribute.String("run_date", runDate.Format("2006-01-02"))))
	defer span.End()

	startTime := time.Now()
	tx, err := r.db.BeginTx(ctx, nil)
	if err != nil {
		span.RecordError(err)
		return nil, fmt.Errorf("failed to begin transaction: %w", err)
	}
	defer func() { _ = tx.Rollback() }()

	runID, existingRun, err := r.getOrCreateRun(ctx, tx, runDate)
	if err != nil {
		span.RecordError(err)
		return nil, fmt.Errorf("failed to get or create run: %w", err)
	}

	if existingRun {
		if _, err := tx.ExecContext(ctx,
			`DELETE FROM reconciliation_mismatches WHERE run_id = $1 AND fix_applied_at IS NULL`,
			runID,
		); err != nil {
			span.RecordError(err)
			return nil, fmt.Errorf("failed to clean up previous run mismatches: %w", err)
		}
	}

	if err := r.updateRunStatus(ctx, tx, runID, "IN_PROGRESS"); err != nil {
		span.RecordError(err)
		return nil, fmt.Errorf("failed to update run status: %w", err)
	}

	ledgerEntries, err := r.queryLedgerEntries(ctx, tx, start.UTC(), end.UTC())
	if err != nil {
		span.RecordError(err)
		return nil, fmt.Errorf("failed to query ledger entries: %w", err)
	}

	pspEntries, err := r.queryPSPEntries(ctx, tx, runDate)
	if err != nil {
		span.RecordError(err)
		return nil, fmt.Errorf("failed to query PSP entries: %w", err)
	}

	mismatches := r.detectMismatches(ledgerEntries, pspEntries)
	logger.Info("mismatches detected", "count", len(mismatches), "run_id", runID)

	autoFixedCount := 0
	manualReviewCount := 0
	failedCount := 0
	var firstProcessErr error
	for _, mismatch := range mismatches {
		if err := r.processMismatch(ctx, tx, runID, mismatch); err != nil {
			failedCount++
			if firstProcessErr == nil {
				firstProcessErr = err
			}
			logger.Error("failed to process mismatch", "error", err, "run_id", runID, "transaction_id", mismatch.TransactionID)
			continue
		}
		if mismatch.AutoFixed {
			autoFixedCount++
		} else {
			manualReviewCount++
		}

		if r.pubsub != nil {
			if err := r.pubsub.PublishMismatchEvent(ctx, runID, mismatch); err != nil {
				logger.WarnContext(ctx, "failed to publish mismatch event", "error", err, "run_id", runID)
			}
			if mismatch.DiscrepancyCents > 0 {
				if err := r.pubsub.PublishDiscrepancyAlert(ctx, runID, mismatch, mismatch.DiscrepancyCents); err != nil {
					logger.WarnContext(ctx, "failed to publish discrepancy alert", "error", err, "run_id", runID, "transaction_id", mismatch.TransactionID)
				}
			}
		}
	}

	runStatus := "COMPLETED"
	if failedCount > 0 {
		runStatus = "PARTIAL_FAILED"
	}

	if err := r.updateRunCounts(ctx, tx, runID, len(mismatches), autoFixedCount, manualReviewCount); err != nil {
		span.RecordError(err)
		return nil, fmt.Errorf("failed to update run counts: %w", err)
	}
	if err := r.updateRunStatus(ctx, tx, runID, runStatus); err != nil {
		span.RecordError(err)
		return nil, fmt.Errorf("failed to update run status to %s: %w", runStatus, err)
	}
	if err := tx.Commit(); err != nil {
		span.RecordError(err)
		return nil, fmt.Errorf("failed to commit transaction: %w", err)
	}

	duration := time.Since(startTime)
	span.SetAttributes(
		attribute.Int("reconciliation.mismatches", len(mismatches)),
		attribute.Int("reconciliation.auto_fixed", autoFixedCount),
		attribute.Int("reconciliation.manual_review", manualReviewCount),
		attribute.Int("reconciliation.failed", failedCount),
		attribute.Float64("reconciliation.duration_seconds", duration.Seconds()),
	)

	if r.pubsub != nil {
		if err := r.pubsub.PublishCompletionEvent(ctx, runID, len(mismatches), autoFixedCount, manualReviewCount); err != nil {
			logger.WarnContext(ctx, "failed to publish completion event", "error", err, "run_id", runID)
		}
	}

	res := &ReconciliationResult{
		RunID:             runID,
		MismatchCount:     len(mismatches),
		AutoFixedCount:    autoFixedCount,
		ManualReviewCount: manualReviewCount,
		Duration:          duration,
	}
	if failedCount > 0 {
		err := fmt.Errorf("failed to process %d mismatch(es): %w", failedCount, firstProcessErr)
		span.RecordError(err)
		return res, err
	}
	return res, nil
}

func (r *DBReconciler) getOrCreateRun(ctx context.Context, tx *sql.Tx, runDate time.Time) (int64, bool, error) {
	var runID int64
	dateStr := runDate.Format("2006-01-02")
	err := tx.QueryRowContext(ctx, `SELECT run_id FROM reconciliation_runs WHERE run_date = $1`, dateStr).Scan(&runID)
	if err == nil {
		return runID, true, nil
	}
	if err != sql.ErrNoRows {
		return 0, false, fmt.Errorf("query failed: %w", err)
	}
	err = tx.QueryRowContext(ctx,
		`INSERT INTO reconciliation_runs (run_date, scheduled_at, status)
		VALUES ($1, NOW(), 'PENDING')
		ON CONFLICT (run_date) DO UPDATE SET status = 'PENDING', scheduled_at = NOW()
		RETURNING run_id`,
		dateStr).Scan(&runID)
	return runID, false, err
}

func (r *DBReconciler) updateRunStatus(ctx context.Context, tx *sql.Tx, runID int64, status string) error {
	_, err := tx.ExecContext(ctx, `UPDATE reconciliation_runs SET status = $1 WHERE run_id = $2`, status, runID)
	return err
}

func (r *DBReconciler) updateRunCounts(ctx context.Context, tx *sql.Tx, runID int64, mismatchCount, autoFixedCount, manualReviewCount int) error {
	_, err := tx.ExecContext(ctx,
		`UPDATE reconciliation_runs
		SET mismatch_count = $1, auto_fixed_count = $2, manual_review_count = $3
		WHERE run_id = $4`,
		mismatchCount, autoFixedCount, manualReviewCount, runID)
	return err
}

func (r *DBReconciler) queryLedgerEntries(ctx context.Context, tx *sql.Tx, start, end time.Time) (map[string]string, error) {
	rows, err := tx.QueryContext(ctx,
		`SELECT transaction_id, amount_cents FROM payment_ledger WHERE created_at >= $1 AND created_at < $2`,
		start, end)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	entries := make(map[string]string)
	for rows.Next() {
		var txnID string
		var amount int64
		if err := rows.Scan(&txnID, &amount); err != nil {
			return nil, fmt.Errorf("failed to scan ledger entry: %w", err)
		}
		entries[txnID] = fmt.Sprintf("%d", amount)
	}
	return entries, rows.Err()
}

func (r *DBReconciler) queryPSPEntries(ctx context.Context, tx *sql.Tx, runDate time.Time) (map[string]string, error) {
	rows, err := tx.QueryContext(ctx,
		`SELECT transaction_id, amount_cents FROM psp_export_snapshot WHERE export_date = $1`,
		runDate.UTC().Format("2006-01-02"))
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	entries := make(map[string]string)
	for rows.Next() {
		var txnID string
		var amount int64
		if err := rows.Scan(&txnID, &amount); err != nil {
			return nil, fmt.Errorf("failed to scan PSP entry: %w", err)
		}
		entries[txnID] = fmt.Sprintf("%d", amount)
	}
	return entries, rows.Err()
}

func (r *DBReconciler) detectMismatches(ledger, psp map[string]string) []MismatchDetail {
	var mismatches []MismatchDetail

	for txnID, pspAmount := range psp {
		if ledgerAmount, exists := ledger[txnID]; !exists {
			diffCents := calculateDiscrepancyCents("", pspAmount)
			mismatches = append(mismatches, MismatchDetail{
				TransactionID:    txnID,
				PSPAmount:        pspAmount,
				DiscrepancyCents: diffCents,
				Reason:           "missing_ledger_entry",
				Severity:         ClassifySeverity(diffCents, "missing_ledger_entry", 0),
				AgeBucket:        AgeBucketFresh,
				ManualReview:     true,
			})
		} else if ledgerAmount != pspAmount {
			diffCents := calculateDiscrepancyCents(ledgerAmount, pspAmount)
			switch {
			case WithinRoundingTolerance(ledgerAmount, pspAmount, FeeTaxRoundingToleranceCents):
				mismatches = append(mismatches, MismatchDetail{
					TransactionID:    txnID,
					LedgerAmount:     ledgerAmount,
					PSPAmount:        pspAmount,
					DiscrepancyCents: diffCents,
					Reason:           "fee_tax_rounding_within_tolerance",
					Severity:         ClassifySeverity(diffCents, "fee_tax_rounding_within_tolerance", 0),
					AgeBucket:        AgeBucketFresh,
					AutoFixed:        true,
				})
			case ShouldAutoFix(ledgerAmount, pspAmount):
				mismatches = append(mismatches, MismatchDetail{
					TransactionID:    txnID,
					LedgerAmount:     ledgerAmount,
					PSPAmount:        pspAmount,
					DiscrepancyCents: diffCents,
					Reason:           "amount_mismatch_auto_fixable",
					Severity:         ClassifySeverity(diffCents, "amount_mismatch_auto_fixable", 0),
					AgeBucket:        AgeBucketFresh,
					AutoFixed:        true,
				})
			default:
				mismatches = append(mismatches, MismatchDetail{
					TransactionID:    txnID,
					LedgerAmount:     ledgerAmount,
					PSPAmount:        pspAmount,
					DiscrepancyCents: diffCents,
					Reason:           "amount_mismatch_manual_review",
					Severity:         ClassifySeverity(diffCents, "amount_mismatch_manual_review", 0),
					AgeBucket:        AgeBucketFresh,
					ManualReview:     true,
				})
			}
		}
	}

	for txnID, ledgerAmount := range ledger {
		if _, exists := psp[txnID]; !exists {
			diffCents := calculateDiscrepancyCents(ledgerAmount, "")
			mismatches = append(mismatches, MismatchDetail{
				TransactionID:    txnID,
				LedgerAmount:     ledgerAmount,
				DiscrepancyCents: diffCents,
				Reason:           "missing_psp_export",
				Severity:         ClassifySeverity(diffCents, "missing_psp_export", 0),
				AgeBucket:        AgeBucketFresh,
				ManualReview:     true,
			})
		}
	}

	SortMismatchDetails(mismatches)
	return mismatches
}

func (r *DBReconciler) processMismatch(ctx context.Context, tx *sql.Tx, runID int64, mismatch MismatchDetail) error {
	manualReview := !mismatch.AutoFixed
	ledgerAmt := formatCentsAsDecimal(mismatch.LedgerAmount)
	pspAmt := formatCentsAsDecimal(mismatch.PSPAmount)

	_, err := tx.ExecContext(ctx,
		`INSERT INTO reconciliation_mismatches
		(run_id, transaction_id, ledger_amount, psp_amount, discrepancy_reason, auto_fixed, manual_review_required, created_at)
		VALUES ($1, $2, $3, $4, $5, $6, $7, NOW())`,
		runID, mismatch.TransactionID, ledgerAmt, pspAmt, mismatch.Reason, mismatch.AutoFixed, manualReview)
	return err
}
