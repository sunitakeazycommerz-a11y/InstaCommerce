package reconciliation

import (
	"database/sql"
	"time"
)

type ReconciliationRun struct {
	RunID              int64
	RunDate            time.Time
	ScheduledAt        time.Time
	StartedAt          *time.Time
	CompletedAt        *time.Time
	MismatchCount      int
	AutoFixedCount     int
	ManualReviewCount  int
	Status             string
	CreatedAt          time.Time
}

type ReconciliationMismatch struct {
	MismatchID          int64
	RunID               int64
	TransactionID       string
	LedgerAmount        string
	PSPAmount           string
	DiscrepancyAmount   string
	DiscrepancyReason   string
	AutoFixed           bool
	ManualReviewRequired bool
	FixAppliedAt        *time.Time
	CreatedAt           time.Time
}

type ReconciliationFix struct {
	FixID       int64
	MismatchID  int64
	FixAppliedAt time.Time
	FixAction   string
	OperatorID  sql.NullString
	Notes       sql.NullString
	CreatedAt   time.Time
}

type MismatchDetail struct {
	TransactionID string
	LedgerAmount  string
	PSPAmount     string
	Reason        string
	AutoFixed     bool
	ManualReview  bool
}

type FixRequest struct {
	FixAction  string `json:"fix_action"`
	OperatorID *string `json:"operator_id,omitempty"`
	Notes      *string `json:"notes,omitempty"`
}

type ReviewRequest struct {
	ManualReviewRequired bool `json:"manual_review_required"`
}
