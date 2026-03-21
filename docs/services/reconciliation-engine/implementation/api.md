# Reconciliation Engine HTTP API

**Wave 36 Track C** · Database-backed reconciliation with HTTP API for querying and managing reconciliation results

---

## Overview

The reconciliation engine exposes HTTP endpoints for:
- Querying reconciliation runs (paginated)
- Fetching mismatches for a specific run
- Marking mismatches for manual review
- Applying fixes to mismatches

All endpoints return JSON responses with appropriate HTTP status codes.

---

## Endpoints

### 1. List Reconciliation Runs

**Endpoint:** `GET /reconciliation/runs`

**Query Parameters:**
- `limit` (optional, default=50, max=1000): Maximum number of runs to return
- `offset` (optional, default=0): Number of runs to skip (pagination)

**Response:** `200 OK`

```json
{
  "runs": [
    {
      "run_id": 1,
      "run_date": "2026-03-20",
      "status": "COMPLETED",
      "mismatch_count": 12,
      "auto_fixed_count": 10,
      "manual_review_count": 2,
      "started_at": "2026-03-20T02:00:15Z",
      "completed_at": "2026-03-20T02:03:45Z"
    },
    {
      "run_id": 2,
      "run_date": "2026-03-21",
      "status": "IN_PROGRESS",
      "mismatch_count": 5,
      "auto_fixed_count": 3,
      "manual_review_count": 0,
      "started_at": "2026-03-21T02:00:10Z",
      "completed_at": null
    }
  ],
  "count": 2
}
```

**Error Responses:**
- `500 Internal Server Error`: Database query failed

---

### 2. Get Mismatches for a Run

**Endpoint:** `GET /reconciliation/runs/{runId}/mismatches`

**Path Parameters:**
- `runId` (required): Reconciliation run ID

**Response:** `200 OK`

```json
{
  "mismatches": [
    {
      "mismatch_id": 101,
      "transaction_id": "psp-1001",
      "ledger_amount": "95000",
      "psp_amount": "100000",
      "discrepancy_amount": "-5000",
      "discrepancy_reason": "amount_mismatch",
      "auto_fixed": false,
      "manual_review_required": true,
      "fix_applied_at": null
    },
    {
      "mismatch_id": 102,
      "transaction_id": "psp-1002",
      "ledger_amount": null,
      "psp_amount": "50000",
      "discrepancy_amount": "-50000",
      "discrepancy_reason": "missing_ledger_entry",
      "auto_fixed": true,
      "manual_review_required": false,
      "fix_applied_at": "2026-03-20T02:02:30Z"
    }
  ],
  "count": 2
}
```

**Error Responses:**
- `400 Bad Request`: Invalid run ID
- `500 Internal Server Error`: Database query failed

---

### 3. Mark Mismatch for Manual Review

**Endpoint:** `POST /reconciliation/mismatches/{mismatchId}/review`

**Path Parameters:**
- `mismatchId` (required): Mismatch ID

**Request Body:**

```json
{
  "manual_review_required": true
}
```

**Response:** `200 OK`

```json
{
  "status": "reviewed"
}
```

**Error Responses:**
- `400 Bad Request`: Invalid mismatch ID or request body
- `500 Internal Server Error`: Database update failed

---

### 4. Apply Fix to a Mismatch

**Endpoint:** `POST /reconciliation/mismatches/{mismatchId}/fix`

**Path Parameters:**
- `mismatchId` (required): Mismatch ID

**Request Body:**

```json
{
  "fix_action": "AUTO_ADJUSTMENT",
  "operator_id": "operator-123",
  "notes": "Adjusted for rounding difference"
}
```

**Fix Actions:**
- `AUTO_ADJUSTMENT`: Automatically adjust ledger amount to match PSP
- `MANUAL_OVERRIDE`: Operator manually corrected the discrepancy
- `AUTO_REVERSE`: Reversed a transaction in the ledger

**Response:** `200 OK`

```json
{
  "status": "fix_applied"
}
```

**Error Responses:**
- `400 Bad Request`: Invalid mismatch ID, missing fix_action, or malformed request body
- `500 Internal Server Error`: Database update failed

---

## Example Usage

### Query Recent Reconciliation Runs

```bash
curl -X GET "http://localhost:8107/reconciliation/runs?limit=10&offset=0"
```

### Get Mismatches for Run #1

```bash
curl -X GET "http://localhost:8107/reconciliation/runs/1/mismatches"
```

### Mark Mismatch #101 for Manual Review

```bash
curl -X POST "http://localhost:8107/reconciliation/mismatches/101/review" \
  -H "Content-Type: application/json" \
  -d '{
    "manual_review_required": true
  }'
```

### Apply Fix to Mismatch #102

```bash
curl -X POST "http://localhost:8107/reconciliation/mismatches/102/fix" \
  -H "Content-Type: application/json" \
  -d '{
    "fix_action": "AUTO_ADJUSTMENT",
    "operator_id": "ops-456",
    "notes": "Rounding difference <0.01%, auto-adjusted"
  }'
```

---

## Error Handling

All error responses follow this format:

```json
{
  "error": "Human-readable error message",
  "details": "Optional technical details"
}
```

**Common HTTP Status Codes:**
- `200 OK`: Request succeeded
- `400 Bad Request`: Invalid input (malformed JSON, invalid IDs, missing required fields)
- `404 Not Found`: Resource not found (if endpoint path is invalid)
- `405 Method Not Allowed`: Wrong HTTP method for endpoint
- `500 Internal Server Error`: Server-side error (database connection, transaction failure)
- `503 Service Unavailable`: Database is unavailable

---

## Rate Limiting & Pagination

- **Default page size:** 50 runs per request
- **Maximum page size:** 1000 runs per request
- **Pagination:** Use `offset` and `limit` query parameters for pagination
- **No rate limiting:** Endpoints are not rate-limited (apply at API gateway if needed)

---

## SLO Targets

| Endpoint | P99 Latency | Notes |
|----------|-------------|-------|
| List Runs | <100ms | Index on (run_date DESC) |
| Get Mismatches | <150ms | Index on (run_id) + (transaction_id) |
| Review/Fix | <200ms | Single-row update + audit trail insert |

---

## Database Indexes

The API relies on the following indexes for performance:
- `idx_reconciliation_runs_date` on `(run_date DESC)` — for recent runs query
- `idx_mismatches_run_id` on `(run_id)` — for mismatch listing
- `idx_mismatches_transaction_id` on `(transaction_id)` — for transaction lookup

---

## Integration with Reconciliation Scheduler

The HTTP API can be used to:
1. **Monitor** ongoing and completed reconciliation runs
2. **Investigate** mismatches in the latest run
3. **Manually override** auto-fixed mismatches if needed
4. **Audit** fix actions with operator ID and notes

Example workflow:
1. Wait for daily reconciliation run to complete (2 AM UTC by default)
2. Query `/reconciliation/runs` to see latest run results
3. If `manual_review_count > 0`, fetch mismatches via `/reconciliation/runs/{runId}/mismatches`
4. Review each mismatch and call `/reconciliation/mismatches/{id}/review` or `/reconciliation/mismatches/{id}/fix` as needed
