package cdc

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"time"
)

func parsePaymentLedgerRecord(after map[string]interface{}, tsMs, lsn int64) (paymentLedgerRecord, error) {
	if after == nil {
		return paymentLedgerRecord{}, errors.New("missing after image in payments event")
	}

	paymentID := extractString(after, "payment_id", "paymentId", "id")
	orderID := extractString(after, "order_id", "orderId")
	pspRef := extractString(after, "psp_reference", "pspReference")
	txnID := extractString(after, "transaction_id", "transactionId")
	if txnID == "" {
		txnID = pspRef
	}
	if txnID == "" {
		txnID = paymentID
	}
	if paymentID == "" {
		paymentID = txnID
	}
	if txnID == "" || paymentID == "" {
		return paymentLedgerRecord{}, errors.New("missing payment identifier in payments CDC event")
	}

	amountCents, ok := extractInt64(after, "amount_cents", "amountCents", "amount")
	if !ok {
		return paymentLedgerRecord{}, fmt.Errorf("missing or invalid amount_cents for payment %s", paymentID)
	}
	capturedCents, _ := extractInt64(after, "captured_cents", "capturedCents")
	refundedCents, _ := extractInt64(after, "refunded_cents", "refundedCents")

	currency := extractString(after, "currency")
	if currency == "" {
		currency = "INR"
	}
	status := extractString(after, "status")
	if status == "" {
		status = "UNKNOWN"
	}
	paymentMethod := extractString(after, "payment_method", "paymentMethod")

	createdAt := extractTimestamp(after, tsMs, "created_at", "createdAt")
	updatedAt := extractTimestamp(after, tsMs, "updated_at", "updatedAt")

	return paymentLedgerRecord{
		TransactionID: txnID,
		PaymentID:     paymentID,
		OrderID:       orderID,
		PSPReference:  pspRef,
		AmountCents:   amountCents,
		CapturedCents: capturedCents,
		RefundedCents: refundedCents,
		Currency:      currency,
		Status:        status,
		PaymentMethod: paymentMethod,
		SourceLSN:     lsn,
		CreatedAt:     createdAt,
		UpdatedAt:     updatedAt,
	}, nil
}

func upsertPaymentLedger(ctx context.Context, db *sql.DB, entry paymentLedgerRecord) error {
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin payment ledger tx: %w", err)
	}
	defer func() { _ = tx.Rollback() }()

	if entry.TransactionID != entry.PaymentID && entry.PSPReference != "" {
		if _, err := tx.ExecContext(ctx,
			`DELETE FROM payment_ledger
			WHERE transaction_id = $1
			  AND payment_id = $1
			  AND (psp_reference IS NULL OR psp_reference = '')`,
			entry.PaymentID,
		); err != nil {
			return fmt.Errorf("remove placeholder payment ledger row: %w", err)
		}
	}

	_, err = tx.ExecContext(ctx,
		`INSERT INTO payment_ledger (
			transaction_id, payment_id, order_id, psp_reference,
			amount_cents, captured_cents, refunded_cents,
			currency, status, payment_method, source_lsn,
			created_at, updated_at
		) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13)
		ON CONFLICT (transaction_id) DO UPDATE SET
			payment_id = EXCLUDED.payment_id,
			order_id = COALESCE(NULLIF(EXCLUDED.order_id, ''), payment_ledger.order_id),
			psp_reference = COALESCE(NULLIF(EXCLUDED.psp_reference, ''), payment_ledger.psp_reference),
			amount_cents = EXCLUDED.amount_cents,
			captured_cents = EXCLUDED.captured_cents,
			refunded_cents = EXCLUDED.refunded_cents,
			currency = EXCLUDED.currency,
			status = EXCLUDED.status,
			payment_method = COALESCE(NULLIF(EXCLUDED.payment_method, ''), payment_ledger.payment_method),
			source_lsn = GREATEST(payment_ledger.source_lsn, EXCLUDED.source_lsn),
			updated_at = GREATEST(payment_ledger.updated_at, EXCLUDED.updated_at)
		WHERE EXCLUDED.source_lsn >= payment_ledger.source_lsn`,
		entry.TransactionID,
		entry.PaymentID,
		entry.OrderID,
		entry.PSPReference,
		entry.AmountCents,
		entry.CapturedCents,
		entry.RefundedCents,
		entry.Currency,
		entry.Status,
		entry.PaymentMethod,
		entry.SourceLSN,
		entry.CreatedAt,
		entry.UpdatedAt,
	)
	if err != nil {
		return fmt.Errorf("upsert payment ledger row %s: %w", entry.TransactionID, err)
	}
	if err := tx.Commit(); err != nil {
		return fmt.Errorf("commit payment ledger tx: %w", err)
	}
	return nil
}

func extractString(m map[string]interface{}, keys ...string) string {
	for _, k := range keys {
		raw, exists := m[k]
		if !exists || raw == nil {
			continue
		}
		if s, ok := raw.(string); ok {
			if trimmed := strings.TrimSpace(s); trimmed != "" {
				return trimmed
			}
		}
	}
	return ""
}

func extractInt64(m map[string]interface{}, keys ...string) (int64, bool) {
	for _, k := range keys {
		raw, exists := m[k]
		if !exists || raw == nil {
			continue
		}
		switch v := raw.(type) {
		case float64:
			if v >= -9223372036854775808.0 && v < 9223372036854775808.0 && v == float64(int64(v)) {
				return int64(v), true
			}
		case int64:
			return v, true
		case int:
			return int64(v), true
		case json.Number:
			if n, err := v.Int64(); err == nil {
				return n, true
			}
		case string:
			if parsed, err := strconv.ParseInt(strings.TrimSpace(v), 10, 64); err == nil {
				return parsed, true
			}
		}
	}
	return 0, false
}

func extractTimestamp(m map[string]interface{}, fallbackTsMs int64, keys ...string) time.Time {
	for _, k := range keys {
		raw, exists := m[k]
		if !exists || raw == nil {
			continue
		}
		switch v := raw.(type) {
		case string:
			s := strings.TrimSpace(v)
			for _, layout := range []string{time.RFC3339Nano, time.RFC3339, "2006-01-02 15:04:05.999999", "2006-01-02 15:04:05"} {
				if parsed, err := time.Parse(layout, s); err == nil {
					return parsed.UTC()
				}
			}
		case float64:
			if ts, ok := unixTimestampFromNumber(int64(v)); ok {
				return ts
			}
		case int64:
			if ts, ok := unixTimestampFromNumber(v); ok {
				return ts
			}
		case int:
			if ts, ok := unixTimestampFromNumber(int64(v)); ok {
				return ts
			}
		}
	}
	if fallbackTsMs > 0 {
		return time.UnixMilli(fallbackTsMs).UTC()
	}
	return time.Now().UTC()
}

func unixTimestampFromNumber(n int64) (time.Time, bool) {
	if n <= 0 {
		return time.Time{}, false
	}
	switch {
	case n >= 1e14:
		return time.UnixMicro(n).UTC(), true
	case n >= 1e11:
		return time.UnixMilli(n).UTC(), true
	default:
		return time.Unix(n, 0).UTC(), true
	}
}
