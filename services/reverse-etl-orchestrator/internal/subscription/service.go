// Package subscription provides the subscription management service.
package subscription

import (
	"context"
	"database/sql"
	"fmt"
	"time"

	"github.com/google/uuid"
	_ "github.com/jackc/pgx/v5/stdlib"

	"github.com/instacommerce/reverse-etl-orchestrator/internal/config"
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

	// Configure connection pool
	db.SetMaxOpenConns(25)
	db.SetMaxIdleConns(5)
	db.SetConnMaxLifetime(5 * time.Minute)

	// Verify connection
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := db.PingContext(ctx); err != nil {
		return nil, fmt.Errorf("failed to ping database: %w", err)
	}

	return &Service{
		db:  db,
		cfg: cfg,
	}, nil
}

// Close closes the service resources.
func (s *Service) Close() error {
	return s.db.Close()
}

// Ping checks database connectivity.
func (s *Service) Ping(ctx context.Context) error {
	return s.db.PingContext(ctx)
}

// Create creates a new subscription.
func (s *Service) Create(ctx context.Context, req CreateRequest, createdBy string) (*Subscription, error) {
	sub := &Subscription{
		ID:            uuid.New().String(),
		Name:          req.Name,
		Description:   req.Description,
		SourceDomain:  req.SourceDomain,
		SourceEvents:  req.SourceEvents,
		SinkType:      req.SinkType,
		SinkConfig:    req.SinkConfig,
		TransformSpec: req.TransformSpec,
		Schedule:      req.Schedule,
		State:         StateDraft,
		CreatedAt:     time.Now(),
		UpdatedAt:     time.Now(),
		CreatedBy:     createdBy,
	}

	query := `
		INSERT INTO subscriptions (
			subscription_id, name, description, source_domain, 
			sink_type, sink_config, transform_spec, schedule,
			state, created_at, updated_at, created_by
		) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12)
	`

	_, err := s.db.ExecContext(ctx, query,
		sub.ID, sub.Name, sub.Description, sub.SourceDomain,
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
	query := `
		SELECT subscription_id, name, description, source_domain,
			sink_type, sink_config, transform_spec, schedule,
			state, created_at, updated_at, created_by
		FROM subscriptions
		WHERE subscription_id = $1
	`

	sub := &Subscription{}
	err := s.db.QueryRowContext(ctx, query, id).Scan(
		&sub.ID, &sub.Name, &sub.Description, &sub.SourceDomain,
		&sub.SinkType, &sub.SinkConfig, &sub.TransformSpec, &sub.Schedule,
		&sub.State, &sub.CreatedAt, &sub.UpdatedAt, &sub.CreatedBy,
	)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, fmt.Errorf("failed to get subscription: %w", err)
	}

	return sub, nil
}

// List retrieves all subscriptions with optional filters.
func (s *Service) List(ctx context.Context, sourceDomain, sinkType string, state State) ([]*Subscription, error) {
	query := `
		SELECT subscription_id, name, description, source_domain,
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
		err := rows.Scan(
			&sub.ID, &sub.Name, &sub.Description, &sub.SourceDomain,
			&sub.SinkType, &sub.SinkConfig, &sub.TransformSpec, &sub.Schedule,
			&sub.State, &sub.CreatedAt, &sub.UpdatedAt, &sub.CreatedBy,
		)
		if err != nil {
			return nil, fmt.Errorf("failed to scan subscription: %w", err)
		}
		subs = append(subs, sub)
	}

	return subs, nil
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
	query := `
		UPDATE subscriptions
		SET state = $1, updated_at = $2
		WHERE subscription_id = $3
	`

	result, err := s.db.ExecContext(ctx, query, newState, time.Now(), id)
	if err != nil {
		return fmt.Errorf("failed to transition subscription: %w", err)
	}

	rows, _ := result.RowsAffected()
	if rows == 0 {
		return fmt.Errorf("subscription not found: %s", id)
	}

	return nil
}
