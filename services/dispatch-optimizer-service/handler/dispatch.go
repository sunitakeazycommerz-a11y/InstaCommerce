package handler

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"sync"
	"sync/atomic"
	"time"

	"github.com/instacommerce/dispatch-optimizer-service/optimizer"
	"github.com/instacommerce/go-shared/pkg/correlation"
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

// ErrRiderAlreadyLocked indicates a candidate rider is currently locked by a concurrent dispatch.
var ErrRiderAlreadyLocked = errors.New("rider is already locked by concurrent dispatch")

// RiderMutex defines locking and unlocking contracts for candidate rider assignments (Issue #594).
type RiderMutex interface {
	Lock(ctx context.Context, riderID string, ttl time.Duration) (func() error, error)
}

type lockEntry struct {
	expiresAt time.Time
	token     uint64
}

type inMemoryRiderMutex struct {
	mu      sync.Mutex
	locks   map[string]lockEntry
	nextSeq atomic.Uint64
}

// NewInMemoryRiderMutex creates a thread-safe mutex tracker for rider assignments.
func NewInMemoryRiderMutex() RiderMutex {
	return &inMemoryRiderMutex{
		locks: make(map[string]lockEntry),
	}
}

func (m *inMemoryRiderMutex) Lock(_ context.Context, riderID string, ttl time.Duration) (func() error, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	now := time.Now()
	for id, entry := range m.locks {
		if !now.Before(entry.expiresAt) {
			delete(m.locks, id)
		}
	}
	if existing, exists := m.locks[riderID]; exists && now.Before(existing.expiresAt) {
		return nil, fmt.Errorf("rider %s: %w", riderID, ErrRiderAlreadyLocked)
	}

	token := m.nextSeq.Add(1)
	m.locks[riderID] = lockEntry{expiresAt: now.Add(ttl), token: token}
	var unlockOnce sync.Once
	return func() error {
		unlockOnce.Do(func() {
			m.mu.Lock()
			defer m.mu.Unlock()
			if cur, ok := m.locks[riderID]; ok && cur.token == token {
				delete(m.locks, riderID)
			}
		})
		return nil
	}, nil
}

// DispatchAssignRequest is the request payload for POST /dispatch/assign.
type DispatchAssignRequest struct {
	OrderID                  string                 `json:"order_id"`
	StoreID                  string                 `json:"store_id"`
	UserID                   string                 `json:"user_id,omitempty"`
	DeliveryAddress          string                 `json:"delivery_address,omitempty"`
	StorePosition            *optimizer.Position    `json:"store_position,omitempty"`
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

// EventEnvelope conforms to contracts/src/main/resources/schemas/common/EventEnvelope.v1.json.
type EventEnvelope struct {
	ID            string    `json:"id"`
	EventType     string    `json:"eventType"`
	AggregateType string    `json:"aggregateType"`
	AggregateID   string    `json:"aggregateId"`
	EventTime     time.Time `json:"eventTime"`
	SchemaVersion string    `json:"schemaVersion"`
	SourceService string    `json:"source_service"`
	CorrelationID string    `json:"correlation_id,omitempty"`
	Payload       any       `json:"payload"`
}

// RiderAssignedEvent conforms to schemas/rider/RiderAssigned.v1.json (Issue #571).
type RiderAssignedEvent struct {
	RiderID    string  `json:"riderId"`
	OrderID    string  `json:"orderId"`
	StoreID    string  `json:"storeId,omitempty"`
	UserID     string  `json:"userId,omitempty"`
	RiderName  string  `json:"riderName,omitempty"`
	PickupLat  float64 `json:"pickupLat"`
	PickupLng  float64 `json:"pickupLng"`
	DropoffLat float64 `json:"dropoffLat"`
	DropoffLng float64 `json:"dropoffLng"`
	AssignedAt string  `json:"assignedAt"`
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
	UserID           string                 `json:"user_id,omitempty"`
	DeliveryAddress  string                 `json:"delivery_address,omitempty"`
	RiderID          string                 `json:"rider_id"`
	Accepted         bool                   `json:"accepted"`
	Reason           string                 `json:"reason,omitempty"` // "TIMEOUT" or "REJECTED"
	StorePosition    *optimizer.Position    `json:"store_position,omitempty"`
	OrderPosition    optimizer.Position     `json:"order_position"`
	ItemCount        int                    `json:"item_count,omitempty"`
	Weight           float64                `json:"weight,omitempty"`
	SLADeadline      time.Time              `json:"sla_deadline,omitempty"`
	CreatedAt        time.Time              `json:"created_at,omitempty"`
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

func (p *NoopEventPublisher) Publish(_ context.Context, topic string, key string, payload any) error {
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
	if logger == nil {
		logger = slog.Default()
	}
	return func(w http.ResponseWriter, r *http.Request) {
		var locks []func() error
		releaseAll := func() {
			for i := len(locks) - 1; i >= 0; i-- {
				if err := locks[i](); err != nil {
					logger.Warn("dispatch: failed to release rider lock", "error", err)
				}
			}
			locks = nil
		}
		defer releaseAll()
		defer func() {
			if rec := recover(); rec != nil {
				releaseAll()
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

		excludedSet := make(map[string]bool, len(req.ExcludedRiderIDs))
		for _, id := range req.ExcludedRiderIDs {
			excludedSet[id] = true
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

		var selectedAssignment *optimizer.OptimalAssignment
		var selectedRider *optimizer.RiderState
		var lastMatchRes *optimizer.MatchResult

		for {
			eligibleRiders := make([]optimizer.RiderState, 0, len(req.AvailableRiders))
			riderByID := make(map[string]optimizer.RiderState, len(req.AvailableRiders))
			for _, rider := range req.AvailableRiders {
				if !excludedSet[rider.ID] {
					eligibleRiders = append(eligibleRiders, rider)
					riderByID[rider.ID] = rider
				}
			}

			matchRes, err := matcher.Match(ctx, eligibleRiders, orders)
			if err != nil {
				if errors.Is(err, optimizer.ErrNoRidersAvailable) {
					lastMatchRes = matchRes
					break
				}
				logger.Error("dispatch: matching failed", "error", err)
				writeV2Error(w, http.StatusInternalServerError, "dispatch matching failed")
				return
			}
			lastMatchRes = matchRes

			if matchRes == nil || len(matchRes.Assignments) == 0 || len(matchRes.Assignments[0].OrderIDs) == 0 {
				break
			}

			candidate := matchRes.Assignments[0]
			if locker != nil {
				unlock, lockErr := locker.Lock(ctx, candidate.RiderID, DefaultRiderLockTTL)
				if lockErr != nil {
					if !errors.Is(lockErr, ErrRiderAlreadyLocked) {
						logger.Error("dispatch: rider lock backend failure, aborting assignment",
							"rider_id", candidate.RiderID, "error", lockErr)
						writeV2Error(w, http.StatusServiceUnavailable, "failed to acquire rider lock")
						return
					}
					logger.Warn("dispatch: candidate rider locked concurrently, trying next candidate",
						"rider_id", candidate.RiderID, "error", lockErr)
					excludedSet[candidate.RiderID] = true
					continue
				}
				locks = append(locks, unlock)
			}

			selectedAssignment = &candidate
			if rState, ok := riderByID[candidate.RiderID]; ok {
				copyState := rState
				selectedRider = &copyState
			}
			break
		}

		if selectedAssignment == nil {
			logger.Warn("dispatch: no riders available within radius",
				"order_id", req.OrderID,
				"store_id", req.StoreID,
				"escalated", lastMatchRes != nil && lastMatchRes.Escalated,
			)
			isEscalated := (lastMatchRes != nil && lastMatchRes.Escalated) || leadDispatcherAlert
			status := "QUEUED_NO_RIDERS"
			if isEscalated {
				status = "ESCALATED"
			}
			writeV2JSON(w, http.StatusOK, DispatchAssignResponse{
				Status:                   status,
				OrderID:                  req.OrderID,
				RetryAfterSeconds:        int(optimizer.DefaultReevaluationInterval.Seconds()),
				Escalated:                isEscalated,
				EscalationReason:         buildEscalationReason(lastMatchRes, leadDispatcherAlert, req.OrderID, currentAttempt),
				AttemptCount:             currentAttempt,
				AcceptanceTimeoutSeconds: DefaultRiderAcceptanceTimeoutSeconds,
				ExcludedRiderIDs:         req.ExcludedRiderIDs,
				LeadDispatcherAlert:      leadDispatcherAlert,
			})
			return
		}

		pickupPos := req.OrderPosition
		if req.StorePosition != nil {
			pickupPos = *req.StorePosition
		} else if selectedRider != nil {
			pickupPos = selectedRider.Position
		}

		now := time.Now().UTC()
		assignedEvent := &RiderAssignedEvent{
			RiderID:    selectedAssignment.RiderID,
			OrderID:    req.OrderID,
			StoreID:    req.StoreID,
			UserID:     req.UserID,
			PickupLat:  pickupPos.Lat,
			PickupLng:  pickupPos.Lng,
			DropoffLat: req.OrderPosition.Lat,
			DropoffLng: req.OrderPosition.Lng,
			AssignedAt: now.Format(time.RFC3339),
		}

		envelope := &EventEnvelope{
			ID:            newEventID("evt"),
			EventType:     "RiderAssigned",
			AggregateType: "Rider",
			AggregateID:   selectedAssignment.RiderID,
			EventTime:     now,
			SchemaVersion: "v1",
			SourceService: serviceName,
			CorrelationID: correlation.FromContext(ctx),
			Payload:       assignedEvent,
		}

		// Emit canonical EventEnvelope to rider.events topic
		if publisher != nil {
			if pubErr := publisher.Publish(ctx, "rider.events", selectedAssignment.RiderID, envelope); pubErr != nil {
				logger.Error("dispatch: failed to publish RiderAssigned event", "error", pubErr)
			}
		}

		logger.Info("dispatch: assigned rider to order",
			"order_id", req.OrderID,
			"rider_id", selectedAssignment.RiderID,
			"eta_min", selectedAssignment.EstimatedETAMinutes,
			"dist_km", selectedAssignment.TotalDistanceKm,
			"attempt", currentAttempt,
		)

		writeV2JSON(w, http.StatusOK, DispatchAssignResponse{
			Status:                   "ASSIGNED",
			RiderID:                  selectedAssignment.RiderID,
			OrderID:                  req.OrderID,
			EstimatedETAMinutes:      selectedAssignment.EstimatedETAMinutes,
			DistanceKm:               selectedAssignment.TotalDistanceKm,
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
	if logger == nil {
		logger = slog.Default()
	}
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			w.Header().Set("Allow", "POST")
			writeV2Error(w, http.StatusMethodNotAllowed, "method not allowed")
			return
		}

		r.Body = http.MaxBytesReader(w, r.Body, maxBodyBytes)
		decoder := json.NewDecoder(r.Body)
		decoder.DisallowUnknownFields()

		var req RiderResponseRequest
		if err := decoder.Decode(&req); err != nil {
			writeV2Error(w, http.StatusBadRequest, "invalid request body")
			return
		}
		if err := decoder.Decode(&struct{}{}); err != io.EOF {
			writeV2Error(w, http.StatusBadRequest, "request body must contain a single JSON object")
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

		excluded := make([]string, len(req.ExcludedRiderIDs), len(req.ExcludedRiderIDs)+1)
		copy(excluded, req.ExcludedRiderIDs)
		excluded = append(excluded, req.RiderID)

		assignReq := DispatchAssignRequest{
			OrderID:                  req.OrderID,
			StoreID:                  req.StoreID,
			UserID:                   req.UserID,
			DeliveryAddress:          req.DeliveryAddress,
			StorePosition:            req.StorePosition,
			OrderPosition:            req.OrderPosition,
			ItemCount:                req.ItemCount,
			Weight:                   req.Weight,
			SLADeadline:              req.SLADeadline,
			CreatedAt:                req.CreatedAt,
			AvailableRiders:          req.AvailableRiders,
			AttemptCount:             req.AttemptCount,
			ExcludedRiderIDs:         excluded,
			AcceptanceTimeoutSeconds: DefaultRiderAcceptanceTimeoutSeconds,
		}

		assignHandler := HandleDispatchAssignWithLocker(matcher, publisher, locker, logger)
		bodyBytes, err := json.Marshal(assignReq)
		if err != nil {
			logger.Error("dispatch: failed to marshal re-dispatch request", "error", err)
			writeV2Error(w, http.StatusInternalServerError, "failed to prepare re-dispatch request")
			return
		}
		rerouteReq := r.Clone(r.Context())
		rerouteReq.Body = io.NopCloser(bytes.NewReader(bodyBytes))
		rerouteReq.ContentLength = int64(len(bodyBytes))
		assignHandler(w, rerouteReq)
	}
}

func newEventID(prefix string) string {
	var b [8]byte
	if _, err := rand.Read(b[:]); err != nil {
		return fmt.Sprintf("%s-%d", prefix, time.Now().UnixNano())
	}
	return fmt.Sprintf("%s-%s", prefix, hex.EncodeToString(b[:]))
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
