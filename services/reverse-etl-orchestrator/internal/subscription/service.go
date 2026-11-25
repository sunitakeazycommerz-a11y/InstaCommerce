// Package subscription provides the subscription management service.
package subscription

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/google/uuid"
	_ "github.com/jackc/pgx/v5/stdlib"

	"github.com/instacommerce/reverse-etl-orchestrator/db/migration"
	"github.com/instacommerce/reverse-etl-orchestrator/internal/config"
)

var (
	// ErrSubscriptionNotFound indicates that the requested subscription does not exist.
	ErrSubscriptionNotFound = errors.New("subscription not found")
	// ErrInvalidStateTransition indicates a disallowed subscription lifecycle transition.
	ErrInvalidStateTransition = errors.New("invalid subscription state transition")
	// ErrInvalidSubscription indicates invalid subscription input fields.
	ErrInvalidSubscription = errors.New("invalid subscription request")
)

// State represents the subscription lifecycle state.
type State string

const (
	StateDraft     State = "DRAFT"
	StateValidated State = "VALIDATED"
	StateActive    State = "ACTIVE"
	StatePaused    State = "PAUSED"
	StateArchived  State = "ARCHIVED"
)

var allowedStateTransitions = map[State]map[State]bool{
	StateDraft: {
		StateValidated: true,
		StateActive:    true,
		StateArchived:  true,
	},
	StateValidated: {
		StateActive:   true,
		StateArchived: true,
	},
	StateActive: {
		StatePaused:   true,
		StateArchived: true,
	},
	StatePaused: {
		StateActive:   true,
		StateArchived: true,
	},
	StateArchived: {},
}

// CanTransition returns true if transitioning from -> to is permitted.
func CanTransition(from, to State) bool {
	if from == to {
		return from == StateDraft || from == StateValidated || from == StateActive || from == StatePaused || from == StateArchived
	}
	next, ok := allowedStateTransitions[from]
	if !ok {
		return false
	}
	return next[to]
}

// ValidateTransition checks whether a subscription may move from current to next.
func ValidateTransition(current, next State) error {
	if !CanTransition(current, next) {
		return fmt.Errorf("%w: %s -> %s", ErrInvalidStateTransition, current, next)
	}
	return nil
}

// Subscription represents a reverse ETL subscription.
type Subscription struct {
	ID            string    `json:"id"`
	Name          string    `json:"name"`
	Description   string    `json:"description"`
	SourceDomain  string    `json:"source_domain"`
	SourceEvents  []string  `json:"source_events"`
	SinkType      string    `json:"sink_type"`
	SinkConfig    string    `json:"sink_config"`
	TransformSpec string    `json:"transform_spec"`
	Schedule      string    `json:"schedule"`
	State         State     `json:"state"`
	CreatedAt     time.Time `json:"created_at"`
	UpdatedAt     time.Time `json:"updated_at"`
	CreatedBy     string    `json:"created_by"`
}

// CreateRequest represents a request to create a subscription.
type CreateRequest struct {
	Name          string   `json:"name"`
	Description   string   `json:"description"`
	SourceDomain  string   `json:"source_domain"`
	SourceEvents  []string `json:"source_events"`
	SinkType      string   `json:"sink_type"`
	SinkConfig    string   `json:"sink_config"`
	TransformSpec string   `json:"transform_spec"`
	Schedule      string   `json:"schedule"`
}

// Service provides subscription management operations.
type Service struct {
	db  *sql.DB
	cfg *config.Config
}

// NewService creates a new subscription service.
func NewService(cfg *config.Config) (*Service, error) {
	db, err := sql.Open("pgx", cfg.DatabaseURL)
	if err != nil {
		return nil, fmt.Errorf("failed to connect to database: %w", err)
	}

	db.SetMaxOpenConns(25)
	db.SetMaxIdleConns(5)
	db.SetConnMaxLifetime(5 * time.Minute)

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := db.PingContext(ctx); err != nil {
		_ = db.Close()
		return nil, fmt.Errorf("failed to ping database: %w", err)
	}

	svc := &Service{
		db:  db,
		cfg: cfg,
	}
	if err := svc.EnsureSchema(ctx); err != nil {
		_ = db.Close()
		return nil, fmt.Errorf("failed to apply reverse ETL schema: %w", err)
	}

	return svc, nil
}

// EnsureSchema executes the embedded idempotent SQL migrations on startup.
func (s *Service) EnsureSchema(ctx context.Context) error {
	if s == nil || s.db == nil {
		return errors.New("database not initialized")
	}
	_, err := s.db.ExecContext(ctx, migration.V1SchemaSQL)
	return err
}

// NewServiceWithDB constructs a Service from an existing database handle.
func NewServiceWithDB(db *sql.DB, cfg *config.Config) *Service {
	return &Service{db: db, cfg: cfg}
}

// Close closes the service resources.
func (s *Service) Close() error {
	if s == nil || s.db == nil {
		return nil
	}
	return s.db.Close()
}

// Ping checks database connectivity.
func (s *Service) Ping(ctx context.Context) error {
	if s == nil || s.db == nil {
		return errors.New("database not initialized")
	}
	return s.db.PingContext(ctx)
}

// DB exposes the underlying SQL database handle for worker audit persistence.
func (s *Service) DB() *sql.DB {
	if s == nil {
		return nil
	}
	return s.db
}

// Create creates a new subscription in DRAFT state.
func (s *Service) Create(ctx context.Context, req CreateRequest, createdBy string) (*Subscription, error) {
	if strings.TrimSpace(req.Name) == "" || strings.TrimSpace(req.SourceDomain) == "" || strings.TrimSpace(req.SinkType) == "" {
		return nil, fmt.Errorf("%w: name, source_domain, and sink_type are required", ErrInvalidSubscription)
	}
	if s == nil || s.db == nil {
		return nil, errors.New("database not initialized")
	}

	sourceEvents := req.SourceEvents
	if sourceEvents == nil {
		sourceEvents = []string{}
	}
	sourceEventsJSON, err := json.Marshal(sourceEvents)
	if err != nil {
		return nil, fmt.Errorf("marshal source_events: %w", err)
	}

	now := time.Now().UTC()
	sub := &Subscription{
		ID:            uuid.New().String(),
		Name:          strings.TrimSpace(req.Name),
		Description:   req.Description,
		SourceDomain:  strings.TrimSpace(req.SourceDomain),
		SourceEvents:  sourceEvents,
		SinkType:      strings.TrimSpace(req.SinkType),
		SinkConfig:    req.SinkConfig,
		TransformSpec: req.TransformSpec,
		Schedule:      req.Schedule,
		State:         StateDraft,
		CreatedAt:     now,
		UpdatedAt:     now,
		CreatedBy:     createdBy,
	}

	const query = `
		INSERT INTO subscriptions (
			subscription_id, name, description, source_domain, source_events,
			sink_type, sink_config, transform_spec, schedule,
			state, created_at, updated_at, created_by
		) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13)
	`

	_, err = s.db.ExecContext(ctx, query,
		sub.ID, sub.Name, sub.Description, sub.SourceDomain, string(sourceEventsJSON),
		sub.SinkType, sub.SinkConfig, sub.TransformSpec, sub.Schedule,
		sub.State, sub.CreatedAt, sub.UpdatedAt, sub.CreatedBy,
	)
	if err != nil {
		return nil, fmt.Errorf("failed to create subscription: %w", err)
	}

	return sub, nil
}

// Get retrieves a subscription by ID.
func (s *Service) Get(ctx context.Context, id string) (*Subscription, error) {
	if s == nil || s.db == nil {
		return nil, errors.New("database not initialized")
	}

	const query = `
		SELECT subscription_id, name, description, source_domain, source_events,
			sink_type, sink_config, transform_spec, schedule,
			state, created_at, updated_at, created_by
		FROM subscriptions
		WHERE subscription_id = $1
	`

	sub := &Subscription{}
	var sourceEventsRaw string
	err := s.db.QueryRowContext(ctx, query, id).Scan(
		&sub.ID, &sub.Name, &sub.Description, &sub.SourceDomain, &sourceEventsRaw,
		&sub.SinkType, &sub.SinkConfig, &sub.TransformSpec, &sub.Schedule,
		&sub.State, &sub.CreatedAt, &sub.UpdatedAt, &sub.CreatedBy,
	)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, fmt.Errorf("failed to get subscription: %w", err)
	}
	sub.SourceEvents = parseSourceEvents(sourceEventsRaw)

	return sub, nil
}

// List retrieves all subscriptions with optional filters.
func (s *Service) List(ctx context.Context, sourceDomain, sinkType string, state State) ([]*Subscription, error) {
	if s == nil || s.db == nil {
		return nil, errors.New("database not initialized")
	}

	const query = `
		SELECT subscription_id, name, description, source_domain, source_events,
			sink_type, sink_config, transform_spec, schedule,
			state, created_at, updated_at, created_by
		FROM subscriptions
		WHERE ($1 = '' OR source_domain = $1)
		  AND ($2 = '' OR sink_type = $2)
		  AND ($3 = '' OR state = $3)
		ORDER BY created_at DESC
	`

	rows, err := s.db.QueryContext(ctx, query, sourceDomain, sinkType, string(state))
	if err != nil {
		return nil, fmt.Errorf("failed to list subscriptions: %w", err)
	}
	defer rows.Close()

	var subs []*Subscription
	for rows.Next() {
		sub := &Subscription{}
		var sourceEventsRaw string
		err := rows.Scan(
			&sub.ID, &sub.Name, &sub.Description, &sub.SourceDomain, &sourceEventsRaw,
			&sub.SinkType, &sub.SinkConfig, &sub.TransformSpec, &sub.Schedule,
			&sub.State, &sub.CreatedAt, &sub.UpdatedAt, &sub.CreatedBy,
		)
		if err != nil {
			return nil, fmt.Errorf("failed to scan subscription: %w", err)
		}
		sub.SourceEvents = parseSourceEvents(sourceEventsRaw)
		subs = append(subs, sub)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("iterate subscriptions: %w", err)
	}

	return subs, nil
}

func parseSourceEvents(raw string) []string {
	trimmed := strings.TrimSpace(raw)
	if trimmed == "" || trimmed == "null" {
		return []string{}
	}
	var events []string
	if err := json.Unmarshal([]byte(trimmed), &events); err == nil && events != nil {
		return events
	}
	return []string{}
}

// Validate marks a DRAFT subscription as VALIDATED.
func (s *Service) Validate(ctx context.Context, id string) error {
	return s.transition(ctx, id, StateValidated)
}

// Activate transitions a subscription to ACTIVE state.
func (s *Service) Activate(ctx context.Context, id string) error {
	return s.transition(ctx, id, StateActive)
}

// Pause transitions a subscription to PAUSED state.
func (s *Service) Pause(ctx context.Context, id string) error {
	return s.transition(ctx, id, StatePaused)
}

// Archive transitions a subscription to ARCHIVED state.
func (s *Service) Archive(ctx context.Context, id string) error {
	return s.transition(ctx, id, StateArchived)
}

func (s *Service) transition(ctx context.Context, id string, newState State) error {
	if strings.TrimSpace(id) == "" {
		return fmt.Errorf("%w: empty id", ErrSubscriptionNotFound)
	}
	if s == nil || s.db == nil {
		return errors.New("database not initialized")
	}

	var currentStateRaw string
	err := s.db.QueryRowContext(ctx, `SELECT state FROM subscriptions WHERE subscription_id = $1`, id).Scan(&currentStateRaw)
	if errors.Is(err, sql.ErrNoRows) {
		return fmt.Errorf("%w: %s", ErrSubscriptionNotFound, id)
	}
	if err != nil {
		return fmt.Errorf("failed to load subscription state: %w", err)
	}

	currentState := State(currentStateRaw)
	if err := ValidateTransition(currentState, newState); err != nil {
		return err
	}
	if currentState == newState {
		return nil
	}

	const updateQuery = `
		UPDATE subscriptions
		SET state = $1, updated_at = $2
		WHERE subscription_id = $3 AND state = $4
	`

	result, err := s.db.ExecContext(ctx, updateQuery, newState, time.Now().UTC(), id, currentState)
	if err != nil {
		return fmt.Errorf("failed to transition subscription: %w", err)
	}

	rows, err := result.RowsAffected()
	if err != nil {
		return fmt.Errorf("rows affected for subscription transition: %w", err)
	}
	if rows == 0 {
		return fmt.Errorf("%w: concurrent modification for %s", ErrInvalidStateTransition, id)
	}

	return nil
}
