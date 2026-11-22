package cdc

import (
	"context"
	"errors"
	"fmt"
	"time"
)

func (c *CDCConsumer) processRunRecord(after map[string]interface{}, _ int64) error {
	entry := &ReconciliationLedgerEntry{}
	if runDate, ok := after["run_date"].(string); ok {
		entry.RunDate = runDate
	}
	if status, ok := after["status"].(string); ok {
		entry.Status = status
	}
	if mismatchCount, ok := after["mismatch_count"].(float64); ok {
		entry.MismatchCount = int(mismatchCount)
	}
	if autoFixedCount, ok := after["auto_fixed_count"].(float64); ok {
		entry.AutoFixedCount = int(autoFixedCount)
	}
	if manualReviewCount, ok := after["manual_review_count"].(float64); ok {
		entry.ManualReviewCount = int(manualReviewCount)
	}
	if entry.RunDate == "" {
		return fmt.Errorf("missing run_date in reconciliation_runs event")
	}

	c.mu.Lock()
	defer c.mu.Unlock()
	c.metrics.eventsReceived++
	c.metrics.eventsProcessed++

	snapshot, exists := c.dailySnapshots[entry.RunDate]
	if !exists {
		snapshot = &DailySnapshot{
			RunDate:      entry.RunDate,
			Transactions: make(map[string]*ReconciliationLedgerEntry),
			Runs:         make([]*ReconciliationLedgerEntry, 0),
		}
		c.dailySnapshots[entry.RunDate] = snapshot
		c.metrics.snapshotsCreated++
	}
	snapshot.Runs = append(snapshot.Runs, entry)
	snapshot.TotalMismatches += entry.MismatchCount
	snapshot.TotalAutoFixed += entry.AutoFixedCount
	snapshot.TotalManualReview += entry.ManualReviewCount
	snapshot.LastUpdate = time.Now()
	return nil
}

func (c *CDCConsumer) processMismatchRecord(after map[string]interface{}, _ int64) error {
	entry := &ReconciliationLedgerEntry{}
	if transactionID, ok := after["transaction_id"].(string); ok {
		entry.TransactionID = transactionID
	}
	if ledgerAmount, ok := after["ledger_amount"].(string); ok {
		entry.LedgerAmount = ledgerAmount
	}
	if pspAmount, ok := after["psp_amount"].(string); ok {
		entry.PSPAmount = pspAmount
	}
	if discrepancyAmount, ok := after["discrepancy_amount"].(string); ok {
		entry.DiscrepancyAmount = discrepancyAmount
	}
	if discrepancyReason, ok := after["discrepancy_reason"].(string); ok {
		entry.DiscrepancyReason = discrepancyReason
	}
	if autoFixed, ok := after["auto_fixed"].(bool); ok {
		entry.AutoFixed = autoFixed
	}
	if manualReviewRequired, ok := after["manual_review_required"].(bool); ok {
		entry.ManualReviewRequired = manualReviewRequired
	}
	if entry.TransactionID == "" {
		return fmt.Errorf("missing transaction_id in reconciliation_mismatches event")
	}

	c.mu.Lock()
	defer c.mu.Unlock()
	c.metrics.eventsReceived++
	c.metrics.eventsProcessed++
	return nil
}

func (c *CDCConsumer) processPaymentRecord(ctx context.Context, after map[string]interface{}, tsMs, lsn int64) error {
	c.mu.Lock()
	c.metrics.eventsReceived++
	c.mu.Unlock()

	entry, err := parsePaymentLedgerRecord(after, tsMs, lsn)
	if err != nil {
		return err
	}
	db := c.db
	if db == nil {
		return errors.New("payment ledger database is not configured")
	}
	if err := upsertPaymentLedger(ctx, db, entry); err != nil {
		return err
	}

	c.mu.Lock()
	c.metrics.eventsProcessed++
	if lsn > c.metrics.lastProcessedLSN {
		c.metrics.lastProcessedLSN = lsn
	}
	c.mu.Unlock()
	return nil
}

func (c *CDCConsumer) AggregateDaily(_ context.Context, runDate time.Time) (*DailySnapshot, error) {
	c.mu.RLock()
	defer c.mu.RUnlock()

	runDateStr := runDate.Format("2006-01-02")
	snapshot, exists := c.dailySnapshots[runDateStr]
	if !exists {
		return nil, fmt.Errorf("no snapshot found for run_date %s", runDateStr)
	}

	result := &DailySnapshot{
		RunDate:           snapshot.RunDate,
		Transactions:      make(map[string]*ReconciliationLedgerEntry),
		Runs:              make([]*ReconciliationLedgerEntry, len(snapshot.Runs)),
		TotalMismatches:   snapshot.TotalMismatches,
		TotalAutoFixed:    snapshot.TotalAutoFixed,
		TotalManualReview: snapshot.TotalManualReview,
		LastUpdate:        snapshot.LastUpdate,
	}
	for k, v := range snapshot.Transactions {
		result.Transactions[k] = v
	}
	copy(result.Runs, snapshot.Runs)
	return result, nil
}

func (c *CDCConsumer) GetMetrics() CDCMetrics {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return *c.metrics
}

func (c *CDCConsumer) PurgeSnapshot(runDate string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	delete(c.dailySnapshots, runDate)
}

func (c *CDCConsumer) GetAllSnapshots() map[string]*DailySnapshot {
	c.mu.RLock()
	defer c.mu.RUnlock()
	result := make(map[string]*DailySnapshot, len(c.dailySnapshots))
	for k, v := range c.dailySnapshots {
		result[k] = v
	}
	return result
}
