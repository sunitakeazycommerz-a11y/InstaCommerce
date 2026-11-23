package cdc

import (
	"database/sql"
	"time"
)

type DebeziumChangeEvent struct {
	Envelope struct {
		Before   map[string]interface{} `json:"before"`
		After    map[string]interface{} `json:"after"`
		Source   SourceMetadata         `json:"source"`
		Op       string                 `json:"op"`
		TsMs     int64                  `json:"ts_ms"`
		Txid     int64                  `json:"txid"`
		LSN      int64                  `json:"lsn"`
		Xmin     *int64                 `json:"xmin"`
		Xmax     *int64                 `json:"xmax"`
		XidXvid  *string                `json:"xid_xvid"`
		Snapshot []string               `json:"snapshot"`
	} `json:"payload"`
}

type SourceMetadata struct {
	Version   string  `json:"version"`
	Connector string  `json:"connector"`
	Name      string  `json:"name"`
	TsMs      int64   `json:"ts_ms"`
	Snapshot  string  `json:"snapshot"`
	Database  string  `json:"database"`
	Schema    string  `json:"schema"`
	Table     string  `json:"table"`
	TxID      int64   `json:"txId"`
	LSN       int64   `json:"lsn"`
	Xmin      *int64  `json:"xmin"`
	Xmax      *int64  `json:"xmax"`
	XidXvid   *string `json:"xid_xvid"`
}

type ReconciliationLedgerEntry struct {
	RunID                int64      `json:"run_id"`
	RunDate              string     `json:"run_date"`
	Status               string     `json:"status"`
	MismatchCount        int        `json:"mismatch_count"`
	AutoFixedCount       int        `json:"auto_fixed_count"`
	ManualReviewCount    int        `json:"manual_review_count"`
	StartedAt            *time.Time `json:"started_at"`
	CompletedAt          *time.Time `json:"completed_at"`
	CreatedAt            time.Time  `json:"created_at"`
	TransactionID        string     `json:"transaction_id,omitempty"`
	LedgerAmount         string     `json:"ledger_amount,omitempty"`
	PSPAmount            string     `json:"psp_amount,omitempty"`
	DiscrepancyAmount    string     `json:"discrepancy_amount,omitempty"`
	DiscrepancyReason    string     `json:"discrepancy_reason,omitempty"`
	AutoFixed            bool       `json:"auto_fixed,omitempty"`
	ManualReviewRequired bool       `json:"manual_review_required,omitempty"`
}

type CDCConsumerConfig struct {
	KafkaBrokers      []string
	KafkaGroupID      string
	CDCTopic          string
	CDCTopics         []string
	DLTTopic          string
	MaxProcessRetries int
	DB                *sql.DB
	MinBytes          int
	MaxBytes          int
	MaxWait           time.Duration
	CommitInterval    time.Duration
	BatchSize         int
	BatchTimeout      time.Duration
}

type DailySnapshot struct {
	RunDate           string
	Transactions      map[string]*ReconciliationLedgerEntry
	Runs              []*ReconciliationLedgerEntry
	TotalMismatches   int
	TotalAutoFixed    int
	TotalManualReview int
	LastUpdate        time.Time
}

type CDCMetrics struct {
	eventsReceived   int64
	eventsProcessed  int64
	eventsFailed     int64
	eventsDLQ        int64
	snapshotsCreated int64
	lastProcessedLSN int64
}

type paymentLedgerRecord struct {
	TransactionID string
	PaymentID     string
	OrderID       string
	PSPReference  string
	AmountCents   int64
	CapturedCents int64
	RefundedCents int64
	Currency      string
	Status        string
	PaymentMethod string
	SourceLSN     int64
	CreatedAt     time.Time
	UpdatedAt     time.Time
}
