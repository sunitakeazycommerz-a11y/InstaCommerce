package handler

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"sync"
	"time"

	"github.com/instacommerce/dispatch-optimizer-service/optimizer"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
)

const (
	// DefaultRiderLockTTL is shortened from 10 minutes to 30 seconds to prevent
	// stranded riders in assignment-in-progress state (Issue #594).
	DefaultRiderLockTTL = 30 * time.Second

	// DefaultRiderAcceptanceTimeoutSeconds is the 45-second SLA for rider acceptance (Issue #581).
	DefaultRiderAcceptanceTimeoutSeconds = 45

	// MaxDispatchAttemptsBeforeAlert triggers lead dispatcher escalation after 3 failures (Issue #581).
	MaxDispatchAttemptsBeforeAlert = 3
)

// RiderMutex defines locking and unlocking contracts for candidate rider assignments (Issue #594).
type RiderMutex interface {
	Lock(ctx context.Context, riderID string, ttl time.Duration) (func() error, error)
}

type inMemoryRiderMutex struct {
	mu    sync.Mutex
	locks map[string]time.Time
}

// NewInMemoryRiderMutex creates a thread-safe mutex tracker for rider assignments.
func NewInMemoryRiderMutex() RiderMutex {
	return &inMemoryRiderMutex{
		locks: make(map[string]time.Time),
	}
}

func (m *inMemoryRiderMutex) Lock(ctx context.Context, riderID string, ttl time.Duration) (func() error, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	now := time.Now()
	if exp, exists := m.locks[riderID]; exists && now.Before(exp) {
		return nil, fmt.Errorf("rider %s is already locked by concurrent dispatch", riderID)
	}

	m.locks[riderID] = now.Add(ttl)
	return func() error {
		m.mu.Lock()
		defer m.mu.Unlock()
		delete(m.locks, riderID)
		return nil
	}, nil
}

// DispatchAssignRequest is the request payload for POST /dispatch/assign.
type DispatchAssignRequest struct {
	OrderID                  string                 `json:"order_id"`
	StoreID                  string                 `json:"store_id"`
	UserID                   string                 `json:"user_id,omitempty"`
	DeliveryAddress          string                 `json:"delivery_address,omitempty"`
	OrderPosition            optimizer.Position     `json:"order_position"`
	ItemCount                int                    `json:"item_count,omitempty"`
	Weight                   float64                `json:"weight,omitempty"`
	SLADeadline              time.Time              `json:"sla_deadline,omitempty"`
	CreatedAt                time.Time              `json:"created_at,omitempty"`
	AvailableRiders          []optimizer.RiderState `json:"available_riders"`
	AttemptCount             int                    `json:"attempt_count,omitempty"`
	ExcludedRiderIDs         []string               `json:"excluded_rider_ids,omitempty"`
	AcceptanceTimeoutSeconds int                    `json:"acceptance_timeout_seconds,omitempty"`
}

// RiderAssignedEvent conforms strictly to schemas/rider/RiderAssigned.v1.json (Issue #571).
type RiderAssignedEvent struct {
	RiderID    string `json:"riderId"`
	OrderID    string `json:"orderId"`
	StoreID    string `json:"storeId,omitempty"`
	UserID     string `json:"userId,omitempty"`
	RiderName  string `json:"riderName,omitempty"`
	AssignedAt string `json:"assignedAt"`
}

// DispatchAssignResponse returns the dispatch decision and assignment event.
type DispatchAssignResponse struct {
	Status                   string              `json:"status"` // "ASSIGNED", "QUEUED_NO_RIDERS", "ESCALATED"
	RiderID                  string              `json:"rider_id,omitempty"`
	OrderID                  string              `json:"order_id"`
	EstimatedETAMinutes      float64             `json:"estimated_eta_minutes,omitempty"`
	DistanceKm               float64             `json:"distance_km,omitempty"`
	RetryAfterSeconds        int                 `json:"retry_after_seconds,omitempty"`
	Escalated                bool                `json:"escalated"`
	EscalationReason         string              `json:"escalation_reason,omitempty"`
	Event                    *RiderAssignedEvent `json:"event,omitempty"`
	AttemptCount             int                 `json:"attempt_count"`
	AcceptanceTimeoutSeconds int                 `json:"acceptance_timeout_seconds"`
	ExcludedRiderIDs         []string            `json:"excluded_rider_ids,omitempty"`
	LeadDispatcherAlert      bool                `json:"lead_dispatcher_alert"`
}

// RiderResponseRequest is the request payload for POST /dispatch/response.
type RiderResponseRequest struct {
	OrderID          string                 `json:"order_id"`
	StoreID          string                 `json:"store_id"`
	RiderID          string                 `json:"rider_id"`
	Accepted         bool                   `json:"accepted"`
	Reason           string                 `json:"reason,omitempty"` // "TIMEOUT" or "REJECTED"
	OrderPosition    optimizer.Position     `json:"order_position"`
	AttemptCount     int                    `json:"attempt_count"`
	ExcludedRiderIDs []string               `json:"excluded_rider_ids"`
	AvailableRiders  []optimizer.RiderState `json:"available_riders"`
}

// EventPublisher defines the contract for emitting canonical Kafka events.
type EventPublisher interface {
	Publish(ctx context.Context, topic string, key string, payload any) error
}

// NoopEventPublisher logs emitted events without external broker requirements.
type NoopEventPublisher struct {
	Logger *slog.Logger
}

func (p *NoopEventPublisher) Publish(ctx context.Context, topic string, key string, payload any) error {
	if p.Logger != nil {
		p.Logger.Info("published event", "topic", topic, "key", key, "payload", payload)
	}
	return nil
}

// HandleDispatchAssign creates an HTTP handler for POST /dispatch/assign with default locker.
func HandleDispatchAssign(matcher *optimizer.BipartiteMatcher, publisher EventPublisher, logger *slog.Logger) http.HandlerFunc {
	return HandleDispatchAssignWithLocker(matcher, publisher, NewInMemoryRiderMutex(), logger)
}

// HandleDispatchAssignWithLocker exposes POST /dispatch/assign with explicit rider locker and panic recovery (Issue #594).
func HandleDispatchAssignWithLocker(matcher *optimizer.BipartiteMatcher, publisher EventPublisher, locker RiderMutex, logger *slog.Logger) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var unlockFunc func() error
		defer func() {
			if rec := recover(); rec != nil {
				if unlockFunc != nil {
					_ = unlockFunc()
				}
				optimizer.HandlerPanics.Inc()
				logger.Error("dispatch: panic recovered in handler, released rider lock", "panic", rec)
				writeV2Error(w, http.StatusInternalServerError, "internal dispatch error")
			}
		}()

		if r.Method != http.MethodPost {
			w.Header().Set("Allow", "POST")
			writeV2Error(w, http.StatusMethodNotAllowed, "method not allowed")
			return
		}

		r.Body = http.MaxBytesReader(w, r.Body, maxBodyBytes)
		decoder := json.NewDecoder(r.Body)
		decoder.DisallowUnknownFields()

		var req DispatchAssignRequest
		if err := decoder.Decode(&req); err != nil {
			logger.Warn("dispatch: invalid request body", "error", err)
			writeV2Error(w, http.StatusBadRequest, "invalid request body")
			return
		}
		if err := decoder.Decode(&struct{}{}); err != io.EOF {
			writeV2Error(w, http.StatusBadRequest, "request body must contain a single JSON object")
			return
		}

		if err := validateDispatchRequest(req); err != nil {
			logger.Warn("dispatch: validation failed", "error", err)
			writeV2Error(w, http.StatusBadRequest, err.Error())
			return
		}

		tracer := otel.Tracer(serviceName)
		ctx, span := tracer.Start(r.Context(), "dispatch.assign")
		defer span.End()
		span.SetAttributes(
			attribute.String("order_id", req.OrderID),
			attribute.String("store_id", req.StoreID),
			attribute.Int("available_riders", len(req.AvailableRiders)),
		)

		createdAt := req.CreatedAt
		if createdAt.IsZero() {
			createdAt = time.Now()
		}

		currentAttempt := req.AttemptCount + 1
		leadDispatcherAlert := currentAttempt >= MaxDispatchAttemptsBeforeAlert
		if leadDispatcherAlert {
			logger.Warn("dispatch: order reached maximum dispatch attempts, alerting lead dispatcher",
				"order_id", req.OrderID,
				"attempt_count", currentAttempt,
			)
		}

		// Filter out excluded riders (previously rejected or timed-out)
		excludedSet := make(map[string]bool, len(req.ExcludedRiderIDs))
		for _, id := range req.ExcludedRiderIDs {
			excludedSet[id] = true
		}

		eligibleRiders := make([]optimizer.RiderState, 0, len(req.AvailableRiders))
		for _, rider := range req.AvailableRiders {
			if !excludedSet[rider.ID] {
				eligibleRiders = append(eligibleRiders, rider)
			}
		}

		orders := []optimizer.OrderRequest{
			{
				ID:          req.OrderID,
				Position:    req.OrderPosition,
				ItemCount:   req.ItemCount,
				Weight:      req.Weight,
				SLADeadline: req.SLADeadline,
				CreatedAt:   createdAt,
			},
		}

		matchRes, err := matcher.Match(ctx, eligibleRiders, orders)
		if err != nil {
			if errors.Is(err, optimizer.ErrNoRidersAvailable) {
				logger.Warn("dispatch: no riders available within radius",
					"order_id", req.OrderID,
					"store_id", req.StoreID,
					"escalated", matchRes != nil && matchRes.Escalated,
				)
				resp := DispatchAssignResponse{
					Status:                   "QUEUED_NO_RIDERS",
					OrderID:                  req.OrderID,
					RetryAfterSeconds:        int(optimizer.DefaultReevaluationInterval.Seconds()),
					Escalated:                (matchRes != nil && matchRes.Escalated) || leadDispatcherAlert,
					EscalationReason:         buildEscalationReason(matchRes, leadDispatcherAlert, req.OrderID, currentAttempt),
					AttemptCount:             currentAttempt,
					AcceptanceTimeoutSeconds: DefaultRiderAcceptanceTimeoutSeconds,
					ExcludedRiderIDs:         req.ExcludedRiderIDs,
					LeadDispatcherAlert:      leadDispatcherAlert,
				}
				if resp.Escalated {
					resp.Status = "ESCALATED"
				}
				writeV2JSON(w, http.StatusOK, resp)
				return
			}
			logger.Error("dispatch: matching failed", "error", err)
			writeV2Error(w, http.StatusInternalServerError, "dispatch matching failed")
			return
		}

		if matchRes == nil || len(matchRes.Assignments) == 0 || len(matchRes.Assignments[0].OrderIDs) == 0 {
			isEscalated := leadDispatcherAlert
			if matchRes != nil && matchRes.Escalated {
				isEscalated = true
			}
			writeV2JSON(w, http.StatusOK, DispatchAssignResponse{
				Status:                   "QUEUED_NO_RIDERS",
				OrderID:                  req.OrderID,
				RetryAfterSeconds:        int(optimizer.DefaultReevaluationInterval.Seconds()),
				Escalated:                isEscalated,
				EscalationReason:         buildEscalationReason(matchRes, leadDispatcherAlert, req.OrderID, currentAttempt),
				AttemptCount:             currentAttempt,
				AcceptanceTimeoutSeconds: DefaultRiderAcceptanceTimeoutSeconds,
				ExcludedRiderIDs:         req.ExcludedRiderIDs,
				LeadDispatcherAlert:      leadDispatcherAlert,
			})
			return
		}

		assignment := matchRes.Assignments[0]

		// Lock candidate rider record with shortened 30s TTL (Issue #594)
		if locker != nil {
			unlock, lockErr := locker.Lock(ctx, assignment.RiderID, DefaultRiderLockTTL)
			if lockErr != nil {
				logger.Warn("dispatch: candidate rider locked concurrently", "rider_id", assignment.RiderID, "error", lockErr)
			} else {
				unlockFunc = unlock
			}
		}

		assignedEvent := &RiderAssignedEvent{
			RiderID:    assignment.RiderID,
			OrderID:    req.OrderID,
			StoreID:    req.StoreID,
			UserID:     req.UserID,
			AssignedAt: time.Now().UTC().Format(time.RFC3339),
		}

		// Emit canonical RiderAssigned event to rider.events topic
		if publisher != nil {
			if pubErr := publisher.Publish(ctx, "rider.events", assignment.RiderID, assignedEvent); pubErr != nil {
				logger.Error("dispatch: failed to publish RiderAssigned event", "error", pubErr)
			}
		}

		logger.Info("dispatch: assigned rider to order",
			"order_id", req.OrderID,
			"rider_id", assignment.RiderID,
			"eta_min", assignment.EstimatedETAMinutes,
			"dist_km", assignment.TotalDistanceKm,
			"attempt", currentAttempt,
		)

		writeV2JSON(w, http.StatusOK, DispatchAssignResponse{
			Status:                   "ASSIGNED",
			RiderID:                  assignment.RiderID,
			OrderID:                  req.OrderID,
			EstimatedETAMinutes:      assignment.EstimatedETAMinutes,
			DistanceKm:               assignment.TotalDistanceKm,
			Event:                    assignedEvent,
			AttemptCount:             currentAttempt,
			AcceptanceTimeoutSeconds: DefaultRiderAcceptanceTimeoutSeconds,
			ExcludedRiderIDs:         req.ExcludedRiderIDs,
			LeadDispatcherAlert:      leadDispatcherAlert,
		})
	}
}

// HandleRiderResponse handles rider acceptance, timeout, and rejection with automated re-dispatch (Issue #581).
func HandleRiderResponse(matcher *optimizer.BipartiteMatcher, publisher EventPublisher, locker RiderMutex, logger *slog.Logger) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			w.Header().Set("Allow", "POST")
			writeV2Error(w, http.StatusMethodNotAllowed, "method not allowed")
			return
		}

		var req RiderResponseRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			writeV2Error(w, http.StatusBadRequest, "invalid request body")
			return
		}

		if req.OrderID == "" || req.RiderID == "" {
			writeV2Error(w, http.StatusBadRequest, "order_id and rider_id are required")
			return
		}

		if req.Accepted {
			logger.Info("dispatch: rider accepted order", "order_id", req.OrderID, "rider_id", req.RiderID)
			writeV2JSON(w, http.StatusOK, map[string]any{
				"status":   "CONFIRMED",
				"order_id": req.OrderID,
				"rider_id": req.RiderID,
			})
			return
		}

		// Rider rejected or 45-second timeout expired -> Trigger automated re-dispatch to second-best match
		logger.Warn("dispatch: rider rejected or acceptance timed out, triggering re-dispatch",
			"order_id", req.OrderID,
			"rider_id", req.RiderID,
			"reason", req.Reason,
			"attempt", req.AttemptCount,
		)

		excluded := append(req.ExcludedRiderIDs, req.RiderID)
		assignReq := DispatchAssignRequest{
			OrderID:                  req.OrderID,
			StoreID:                  req.StoreID,
			OrderPosition:            req.OrderPosition,
			AvailableRiders:          req.AvailableRiders,
			AttemptCount:             req.AttemptCount,
			ExcludedRiderIDs:         excluded,
			AcceptanceTimeoutSeconds: DefaultRiderAcceptanceTimeoutSeconds,
		}

		assignHandler := HandleDispatchAssignWithLocker(matcher, publisher, locker, logger)
		bodyBytes, _ := json.Marshal(assignReq)
		rerouteReq := r.Clone(r.Context())
		rerouteReq.Body = io.NopCloser(bytes.NewReader(bodyBytes))
		assignHandler(w, rerouteReq)
	}
}

func buildEscalationReason(matchRes *optimizer.MatchResult, leadAlert bool, orderID string, attempts int) string {
	if leadAlert {
		return fmt.Sprintf("Order %s exceeded %d dispatch attempts; lead dispatcher alert triggered", orderID, attempts)
	}
	if matchRes != nil && matchRes.EscalationReason != "" {
		return matchRes.EscalationReason
	}
	return ""
}

func validateDispatchRequest(req DispatchAssignRequest) error {
	if req.OrderID == "" {
		return errors.New("order_id is required")
	}
	if req.StoreID == "" {
		return errors.New("store_id is required")
	}
	if err := validatePos(req.OrderPosition); err != nil {
		return fmt.Errorf("order_position: %w", err)
	}
	return nil
}
