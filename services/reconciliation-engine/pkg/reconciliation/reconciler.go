package reconciliation

import (
	"context"
	"database/sql"
	"fmt"
	"log/slog"
	"math/big"
	"strconv"
	"strings"
	"time"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/trace"
)

// MaxAutoFixDiffCents caps automatic discrepancy adjustments at 100 cents ($1.00).
const MaxAutoFixDiffCents int64 = 100

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
	RunID              int64
	MismatchCount      int
	AutoFixedCount     int
	ManualReviewCount  int
	Duration           time.Duration
}

func (r *DBReconciler) ReconcileDailyRun(ctx context.Context, runDate time.Time) (*ReconciliationResult, error) {
	tracer := r.tracer
	if tracer == nil {
		tracer = otel.Tracer("reconciliation")
	}
	logger := r.logger
	if logger == nil {
		logger = slog.Default()
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

	// Get or create run record
	runID, err := r.getOrCreateRun(ctx, tx, runDate)
	if err != nil {
		span.RecordError(err)
		return nil, fmt.Errorf("failed to get or create run: %w", err)
	}

	// Update run status to IN_PROGRESS
	if err := r.updateRunStatus(ctx, tx, runID, "IN_PROGRESS"); err != nil {
		span.RecordError(err)
		return nil, fmt.Errorf("failed to update run status: %w", err)
	}

	// Query ledger and PSP data
	ledgerEntries, err := r.queryLedgerEntries(ctx, tx)
	if err != nil {
		span.RecordError(err)
		return nil, fmt.Errorf("failed to query ledger entries: %w", err)
	}

	pspEntries, err := r.queryPSPEntries(ctx, tx)
	if err != nil {
		span.RecordError(err)
		return nil, fmt.Errorf("failed to query PSP entries: %w", err)
	}

	// Detect mismatches
	mismatches := r.detectMismatches(ledgerEntries, pspEntries)
	logger.Info("mismatches detected", "count", len(mismatches), "run_id", runID)

	// Process mismatches
	autoFixedCount := 0
	manualReviewCount := 0

	for _, mismatch := range mismatches {
		err := r.processMismatch(ctx, tx, runID, mismatch)
		if err != nil {
			logger.Warn("failed to process mismatch", "error", err, "transaction_id", mismatch.TransactionID)
			continue
		}

		if mismatch.AutoFixed {
			autoFixedCount++
		} else {
			manualReviewCount++
		}

		// Publish event
		if r.pubsub != nil {
			if err := r.pubsub.PublishMismatchEvent(ctx, runID, mismatch); err != nil {
				logger.WarnContext(ctx, "failed to publish mismatch event", "error", err, "run_id", runID)
			}
			discrepancyCents := calculateDiscrepancyCents(mismatch.LedgerAmount, mismatch.PSPAmount)
			if discrepancyCents > 0 {
				_ = r.pubsub.PublishDiscrepancyAlert(ctx, runID, mismatch, discrepancyCents)
			}
		}
	}

	// Update run record with final counts
	if err := r.updateRunCounts(ctx, tx, runID, len(mismatches), autoFixedCount, manualReviewCount); err != nil {
		span.RecordError(err)
		return nil, fmt.Errorf("failed to update run counts: %w", err)
	}

	// Update run status to COMPLETED
	if err := r.updateRunStatus(ctx, tx, runID, "COMPLETED"); err != nil {
		span.RecordError(err)
		return nil, fmt.Errorf("failed to update run status to COMPLETED: %w", err)
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
		attribute.Float64("reconciliation.duration_seconds", duration.Seconds()),
	)

	// Publish completion event
	if r.pubsub != nil {
		if err := r.pubsub.PublishCompletionEvent(ctx, runID, len(mismatches), autoFixedCount, manualReviewCount); err != nil {
			logger.WarnContext(ctx, "failed to publish completion event", "error", err, "run_id", runID)
		}
	}

	logger.Info("reconciliation completed",
		"run_id", runID,
		"mismatches", len(mismatches),
		"auto_fixed", autoFixedCount,
		"manual_review", manualReviewCount,
		"duration_ms", duration.Milliseconds(),
	)

	return &ReconciliationResult{
		RunID:             runID,
		MismatchCount:     len(mismatches),
		AutoFixedCount:    autoFixedCount,
		ManualReviewCount: manualReviewCount,
		Duration:          duration,
	}, nil
}

func (r *DBReconciler) getOrCreateRun(ctx context.Context, tx *sql.Tx, runDate time.Time) (int64, error) {
	var runID int64
	err := tx.QueryRowContext(ctx,
		`SELECT run_id FROM reconciliation_runs WHERE run_date = $1`,
		runDate.Format("2006-01-02")).Scan(&runID)

	if err == nil {
		return runID, nil
	}

	if err != sql.ErrNoRows {
		return 0, fmt.Errorf("query failed: %w", err)
	}

	// Create new run
	err = tx.QueryRowContext(ctx,
		`INSERT INTO reconciliation_runs (run_date, scheduled_at, status)
		VALUES ($1, NOW(), 'PENDING')
		RETURNING run_id`,
		runDate.Format("2006-01-02")).Scan(&runID)

	return runID, err
}

func (r *DBReconciler) updateRunStatus(ctx context.Context, tx *sql.Tx, runID int64, status string) error {
	_, err := tx.ExecContext(ctx,
		`UPDATE reconciliation_runs SET status = $1 WHERE run_id = $2`,
		status, runID)
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

func (r *DBReconciler) queryLedgerEntries(ctx context.Context, tx *sql.Tx) (map[string]string, error) {
	rows, err := tx.QueryContext(ctx,
		`SELECT transaction_id, amount_cents
		FROM payment_ledger
		WHERE created_at >= NOW() - INTERVAL '1 day'`)

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

func (r *DBReconciler) queryPSPEntries(ctx context.Context, tx *sql.Tx) (map[string]string, error) {
	rows, err := tx.QueryContext(ctx,
		`SELECT transaction_id, amount_cents
		FROM psp_export_snapshot
		WHERE export_date = CURRENT_DATE`)

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

	// Missing entries require human investigation so they are never marked auto-fixed.
	for txnID, pspAmount := range psp {
		if ledgerAmount, exists := ledger[txnID]; !exists {
			mismatches = append(mismatches, MismatchDetail{
				TransactionID: txnID,
				PSPAmount:     pspAmount,
				Reason:        "missing_ledger_entry",
				AutoFixed:     false,
				ManualReview:  true,
			})
		} else if ledgerAmount != pspAmount {
			if ShouldAutoFix(ledgerAmount, pspAmount) {
				mismatches = append(mismatches, MismatchDetail{
					TransactionID: txnID,
					LedgerAmount:  ledgerAmount,
					PSPAmount:     pspAmount,
					Reason:        "amount_mismatch_auto_fixable",
					AutoFixed:     true,
				})
			} else {
				mismatches = append(mismatches, MismatchDetail{
					TransactionID: txnID,
					LedgerAmount:  ledgerAmount,
					PSPAmount:     pspAmount,
					Reason:        "amount_mismatch_manual_review",
					AutoFixed:     false,
					ManualReview:  true,
				})
			}
		}
	}

	for txnID := range ledger {
		if _, exists := psp[txnID]; !exists {
			mismatches = append(mismatches, MismatchDetail{
				TransactionID: txnID,
				LedgerAmount:  ledger[txnID],
				Reason:        "missing_psp_export",
				AutoFixed:     false,
				ManualReview:  true,
			})
		}
	}

	return mismatches
}

// ShouldAutoFix permits automatic adjustment only when the discrepancy is both
// within 5% of the base amount and at most MaxAutoFixDiffCents (100 cents).
func ShouldAutoFix(ledgerAmountStr, pspAmountStr string) bool {
	ledgerStr := strings.TrimSpace(ledgerAmountStr)
	pspStr := strings.TrimSpace(pspAmountStr)
	if ledgerStr == "" || pspStr == "" {
		return false
	}

	ledgerAmount, ok := new(big.Rat).SetString(ledgerStr)
	if !ok {
		return false
	}
	pspAmount, ok := new(big.Rat).SetString(pspStr)
	if !ok {
		return false
	}

	diff := new(big.Rat).Sub(ledgerAmount, pspAmount)
	diff.Abs(diff)
	if diff.Sign() == 0 {
		return true
	}

	if diff.Cmp(big.NewRat(MaxAutoFixDiffCents, 1)) > 0 {
		return false
	}

	base := new(big.Rat).Abs(ledgerAmount)
	if base.Sign() == 0 {
		base = new(big.Rat).Abs(pspAmount)
	}
	if base.Sign() == 0 {
		return false
	}

	threshold := new(big.Rat).Mul(base, big.NewRat(5, 100))
	return diff.Cmp(threshold) <= 0
}

func parseAmountCents(raw string) int64 {
	s := strings.TrimSpace(raw)
	if s == "" || s == "0.00" || s == "0" {
		return 0
	}
	if !strings.Contains(s, ".") {
		if val, err := strconv.ParseInt(s, 10, 64); err == nil {
			return val
		}
	}
	if rat, ok := new(big.Rat).SetString(s); ok {
		scaled := new(big.Rat).Mul(rat, big.NewRat(100, 1))
		if scaled.IsInt() {
			num := scaled.Num()
			if num.IsInt64() {
				return num.Int64()
			}
		}
		f, _ := scaled.Float64()
		return int64(f)
	}
	return 0
}

func formatCentsAsDecimal(raw string) string {
	trimmed := strings.TrimSpace(raw)
	if trimmed == "" {
		return "0.00"
	}
	if strings.Contains(trimmed, ".") {
		if rat, ok := new(big.Rat).SetString(trimmed); ok {
			return rat.FloatString(2)
		}
		return trimmed
	}
	cents, err := strconv.ParseInt(trimmed, 10, 64)
	if err != nil {
		if rat, ok := new(big.Rat).SetString(trimmed); ok {
			return new(big.Rat).Quo(rat, big.NewRat(100, 1)).FloatString(2)
		}
		return trimmed
	}
	sign := ""
	var abs uint64
	if cents < 0 {
		sign = "-"
		abs = uint64(-(cents+1)) + 1
	} else {
		abs = uint64(cents)
	}
	return fmt.Sprintf("%s%d.%02d", sign, abs/100, abs%100)
}

func calculateDiscrepancyCents(ledgerStr, pspStr string) int64 {
	l := parseAmountCents(ledgerStr)
	p := parseAmountCents(pspStr)
	diff := l - p
	if diff < 0 {
		return -diff
	}
	return diff
}

func (r *DBReconciler) processMismatch(ctx context.Context, tx *sql.Tx, runID int64, mismatch MismatchDetail) error {
	reason := mismatch.Reason
	manualReview := !mismatch.AutoFixed
	ledgerAmt := formatCentsAsDecimal(mismatch.LedgerAmount)
	pspAmt := formatCentsAsDecimal(mismatch.PSPAmount)

	_, err := tx.ExecContext(ctx,
		`INSERT INTO reconciliation_mismatches
		(run_id, transaction_id, ledger_amount, psp_amount, discrepancy_reason, auto_fixed, manual_review_required, created_at)
		VALUES ($1, $2, $3, $4, $5, $6, $7, NOW())`,
		runID, mismatch.TransactionID, ledgerAmt, pspAmt, reason, mismatch.AutoFixed, manualReview)

	return err
}

func (r *DBReconciler) GetReconciliationRuns(ctx context.Context, limit int, offset int) ([]ReconciliationRun, error) {
	rows, err := r.db.QueryContext(ctx,
		`SELECT run_id, run_date, scheduled_at, started_at, completed_at,
			mismatch_count, auto_fixed_count, manual_review_count, status, created_at
		FROM reconciliation_runs
		ORDER BY run_date DESC
		LIMIT $1 OFFSET $2`,
		limit, offset)

	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var runs []ReconciliationRun
	for rows.Next() {
		var run ReconciliationRun
		err := rows.Scan(&run.RunID, &run.RunDate, &run.ScheduledAt, &run.StartedAt,
			&run.CompletedAt, &run.MismatchCount, &run.AutoFixedCount,
			&run.ManualReviewCount, &run.Status, &run.CreatedAt)
		if err != nil {
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
		ORDER BY created_at DESC`,
		runID)

	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var mismatches []ReconciliationMismatch
	for rows.Next() {
		var m ReconciliationMismatch
		err := rows.Scan(&m.MismatchID, &m.RunID, &m.TransactionID, &m.LedgerAmount, &m.PSPAmount,
			&m.DiscrepancyAmount, &m.DiscrepancyReason, &m.AutoFixed, &m.ManualReviewRequired, &m.FixAppliedAt, &m.CreatedAt)
		if err != nil {
			return nil, err
		}
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
	ctx, span := r.tracer.Start(ctx, "reconciliation.apply_fix",
		trace.WithAttributes(attribute.Int64("mismatch_id", mismatchID)))
	defer span.End()

	tx, err := r.db.BeginTx(ctx, nil)
	if err != nil {
		span.RecordError(err)
		return fmt.Errorf("failed to begin transaction: %w", err)
	}
	defer func() { _ = tx.Rollback() }()

	// Get mismatch details
	var runID int64
	var txnID string
	err = tx.QueryRowContext(ctx,
		`SELECT run_id, transaction_id FROM reconciliation_mismatches WHERE mismatch_id = $1`,
		mismatchID).Scan(&runID, &txnID)

	if err != nil {
		span.RecordError(err)
		return fmt.Errorf("failed to get mismatch: %w", err)
	}

	// Record the fix
	_, err = tx.ExecContext(ctx,
		`INSERT INTO reconciliation_fixes (mismatch_id, fix_applied_at, fix_action, operator_id, notes, created_at)
		VALUES ($1, NOW(), $2, $3, $4, NOW())`,
		mismatchID, fixAction, operatorID, notes)

	if err != nil {
		span.RecordError(err)
		return fmt.Errorf("failed to insert fix record: %w", err)
	}

	// Update mismatch as fixed
	_, err = tx.ExecContext(ctx,
		`UPDATE reconciliation_mismatches
		SET auto_fixed = true, fix_applied_at = NOW()
		WHERE mismatch_id = $1`,
		mismatchID)

	if err != nil {
		span.RecordError(err)
		return fmt.Errorf("failed to update mismatch: %w", err)
	}

	if err := tx.Commit(); err != nil {
		span.RecordError(err)
		return fmt.Errorf("failed to commit transaction: %w", err)
	}

	r.logger.Info("fix applied", "mismatch_id", mismatchID, "fix_action", fixAction)
	return nil
}
