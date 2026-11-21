package subscription

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"time"
)

// SyncDarkStoreInventoryParallel syncs inventory across dark stores using a bounded worker pool.
func (wp *WorkerPool) SyncDarkStoreInventoryParallel(
	ctx context.Context,
	jobID, subscriptionID string,
	storeIDs []string,
	fetchStoreInventory func(ctx context.Context, storeID string) ([]InventoryRecord, error),
) (SyncResult, error) {
	start := time.Now()
	res := SyncResult{
		JobID:    jobID,
		SyncType: "dark_store_inventory",
		Status:   "COMPLETED",
	}

	if len(storeIDs) == 0 {
		res.Duration = time.Since(start)
		return res, nil
	}
	if wp.redisClient == nil {
		res.Status = "FAILED"
		res.Error = errors.New("redis client not configured")
		res.Duration = time.Since(start)
		return res, res.Error
	}
	if fetchStoreInventory == nil {
		res.Status = "FAILED"
		res.Error = errors.New("fetchStoreInventory callback is required")
		res.Duration = time.Since(start)
		return res, res.Error
	}

	jobCh := make(chan string, len(storeIDs))
	for _, id := range storeIDs {
		jobCh <- id
	}
	close(jobCh)

	var wg sync.WaitGroup
	var mu sync.Mutex
	var totalRows int64
	var storesSynced int
	var firstErr error

	setFirstErr := func(err error) {
		mu.Lock()
		if firstErr == nil {
			firstErr = err
		}
		mu.Unlock()
	}

	numWorkers := wp.concurrency
	if numWorkers > len(storeIDs) {
		numWorkers = len(storeIDs)
	}

	for i := 0; i < numWorkers; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for storeID := range jobCh {
				if err := ctx.Err(); err != nil {
					setFirstErr(err)
					return
				}

				rows, err := wp.syncSingleStoreInventory(ctx, jobID, storeID, fetchStoreInventory)
				if err != nil {
					setFirstErr(err)
					if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
						return
					}
					continue
				}

				mu.Lock()
				totalRows += rows
				storesSynced++
				mu.Unlock()
			}
		}()
	}

	wg.Wait()

	res.RowsProcessed = totalRows
	res.StoresSynced = storesSynced
	res.Duration = time.Since(start)

	if ctxErr := ctx.Err(); ctxErr != nil {
		res.Status = "CANCELLED"
		res.Error = ctxErr
		wp.recordAudit(ctx, jobID, subscriptionID, "dark_store_inventory", totalRows, "CANCELLED", res.Duration, ctxErr.Error())
		return res, ctxErr
	}
	if firstErr != nil {
		if errors.Is(firstErr, context.Canceled) || errors.Is(firstErr, context.DeadlineExceeded) {
			res.Status = "CANCELLED"
			res.Error = firstErr
			wp.recordAudit(ctx, jobID, subscriptionID, "dark_store_inventory", totalRows, "CANCELLED", res.Duration, firstErr.Error())
			return res, firstErr
		}
		res.Status = "FAILED"
		res.Error = firstErr
		wp.recordAudit(ctx, jobID, subscriptionID, "dark_store_inventory", totalRows, "FAILED", res.Duration, firstErr.Error())
		return res, firstErr
	}

	wp.recordSuccessMetric("dark_store_inventory", totalRows)
	wp.recordAudit(ctx, jobID, subscriptionID, "dark_store_inventory", totalRows, "COMPLETED", res.Duration, "")
	return res, nil
}

func (wp *WorkerPool) syncSingleStoreInventory(
	ctx context.Context,
	jobID, storeID string,
	fetchStoreInventory func(ctx context.Context, storeID string) ([]InventoryRecord, error),
) (int64, error) {
	storeStart := time.Now()
	items, err := fetchStoreInventory(ctx, storeID)
	if err != nil {
		return 0, fmt.Errorf("failed fetching inventory for store %s: %w", storeID, err)
	}

	tempKey := fmt.Sprintf("store:inventory:temp:%s:%s", jobID, storeID)
	targetKey := fmt.Sprintf("store:inventory:%s", storeID)

	if len(items) > 0 {
		kvPairs := make([]any, 0, len(items)*2)
		for _, item := range items {
			if err := wp.rateLimiter.Acquire(ctx); err != nil {
				return 0, fmt.Errorf("rate limiter acquire cancelled for store %s: %w", storeID, err)
			}
			kvPairs = append(kvPairs, item.SKU, fmt.Sprintf("%d:%d", item.Available, item.Reserved))
		}

		if err := wp.syncHashWithTempSwap(
			ctx,
			"dark_store_inventory",
			tempKey,
			targetKey,
			24*time.Hour,
			"inventory for store "+storeID,
			kvPairs...,
		); err != nil {
			return 0, err
		}
	}

	wp.observeStoreDuration(storeID, time.Since(storeStart).Seconds())
	return int64(len(items)), nil
}

func (wp *WorkerPool) recordAudit(_ context.Context, jobID, subscriptionID, syncType string, rows int64, status string, duration time.Duration, errMsg string) {
	if wp.db == nil {
		return
	}

	auditCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	const query = `
		INSERT INTO reverse_etl_sync_audit (
			job_id, subscription_id, sync_type, rows_processed,
			status, duration_ms, error_message, created_at
		) VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
		ON CONFLICT (job_id) DO UPDATE SET
			rows_processed = EXCLUDED.rows_processed,
			status = EXCLUDED.status,
			duration_ms = EXCLUDED.duration_ms,
			error_message = EXCLUDED.error_message
	`

	_, err := wp.db.ExecContext(auditCtx, query,
		jobID, subscriptionID, syncType, rows,
		status, duration.Milliseconds(), errMsg, time.Now(),
	)
	if err != nil {
		wp.logger.Warn("failed to write reverse ETL audit log (non-fatal)", "error", err, "job_id", jobID)
	}
}
