package handler

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"log/slog"
	"math"
	"net/http"
	"time"

	"github.com/instacommerce/dispatch-optimizer-service/optimizer"
	"github.com/instacommerce/go-shared/pkg/correlation"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
)

// HandleDispatchAssign creates an HTTP handler for POST /dispatch/assign with default locker.
func HandleDispatchAssign(matcher *optimizer.BipartiteMatcher, publisher EventPublisher, logger *slog.Logger) http.HandlerFunc {
	return HandleDispatchAssignWithLocker(matcher, publisher, NewInMemoryRiderMutex(), logger)
}

// HandleDispatchAssignWithLocker exposes POST /dispatch/assign with rider locking and panic recovery.
func HandleDispatchAssignWithLocker(matcher *optimizer.BipartiteMatcher, publisher EventPublisher, locker RiderMutex, logger *slog.Logger) http.HandlerFunc {
	if logger == nil {
		logger = slog.Default()
	}
	return func(w http.ResponseWriter, r *http.Request) {
		var req DispatchAssignRequest
		if !DecodePostJSON(w, r, maxBodyBytes, logger, &req) {
			return
		}

		dispatchAndRespond(r.Context(), w, req, matcher, publisher, locker, logger)
	}
}

func dispatchAndRespond(
	ctx context.Context,
	w http.ResponseWriter,
	req DispatchAssignRequest,
	matcher *optimizer.BipartiteMatcher,
	publisher EventPublisher,
	locker RiderMutex,
	logger *slog.Logger,
) {
	if err := validateDispatchRequest(req); err != nil {
		logger.Warn("dispatch: validation failed", "error", err)
		WriteError(w, http.StatusBadRequest, err.Error())
		return
	}

	resp, status, errMsg := executeDispatchAssign(ctx, req, matcher, publisher, locker, logger)
	if errMsg != "" {
		WriteError(w, status, errMsg)
		return
	}
	WriteJSON(w, status, resp)
}

func executeDispatchAssign(
	ctx context.Context,
	req DispatchAssignRequest,
	matcher *optimizer.BipartiteMatcher,
	publisher EventPublisher,
	locker RiderMutex,
	logger *slog.Logger,
) (resp DispatchAssignResponse, statusCode int, errMsg string) {
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
			resp = DispatchAssignResponse{}
			statusCode = http.StatusInternalServerError
			errMsg = "internal dispatch error"
		}
	}()

	tracer := otel.Tracer(serviceName)
	ctx, span := tracer.Start(ctx, "dispatch.assign")
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

	orders := []optimizer.OrderRequest{{
		ID:                req.OrderID,
		StoreID:           req.StoreID,
		StorePosition:     req.StorePosition,
		Position:          req.OrderPosition,
		ItemCount:         req.ItemCount,
		Weight:            req.Weight,
		VolumeLiters:      req.VolumeLiters,
		IsExpressOrder:    req.IsExpressOrder,
		RequiresColdChain: req.RequiresColdChain,
		ColdChainTier:     req.ColdChainTier,
		SLADeadline:       req.SLADeadline,
		CreatedAt:         createdAt,
	}}

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
			return DispatchAssignResponse{}, http.StatusInternalServerError, "dispatch matching failed"
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
					return DispatchAssignResponse{}, http.StatusServiceUnavailable, "failed to acquire rider lock"
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
		isEscalated := (lastMatchRes != nil && lastMatchRes.Escalated) || leadDispatcherAlert
		status := "QUEUED_NO_RIDERS"
		if isEscalated {
			status = "ESCALATED"
		}
		return DispatchAssignResponse{
			Status:                   status,
			OrderID:                  req.OrderID,
			RetryAfterSeconds:        int(optimizer.DefaultReevaluationInterval.Seconds()),
			Escalated:                isEscalated,
			EscalationReason:         buildEscalationReason(lastMatchRes, leadDispatcherAlert, req.OrderID, currentAttempt),
			AttemptCount:             currentAttempt,
			AcceptanceTimeoutSeconds: DefaultRiderAcceptanceTimeoutSeconds,
			ExcludedRiderIDs:         req.ExcludedRiderIDs,
			LeadDispatcherAlert:      leadDispatcherAlert,
		}, http.StatusOK, ""
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

	if publisher != nil {
		if pubErr := publisher.Publish(ctx, "rider.events", selectedAssignment.RiderID, envelope); pubErr != nil {
			logger.Error("dispatch: failed to publish RiderAssigned event", "error", pubErr)
		}
	}

	return DispatchAssignResponse{
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
	}, http.StatusOK, ""
}

// HandleRiderResponse handles rider acceptance, timeout, and rejection with automated re-dispatch.
func HandleRiderResponse(matcher *optimizer.BipartiteMatcher, publisher EventPublisher, locker RiderMutex, logger *slog.Logger) http.HandlerFunc {
	if logger == nil {
		logger = slog.Default()
	}
	return func(w http.ResponseWriter, r *http.Request) {
		var req RiderResponseRequest
		if !DecodePostJSON(w, r, maxBodyBytes, logger, &req) {
			return
		}
		if req.OrderID == "" || req.RiderID == "" {
			WriteError(w, http.StatusBadRequest, "order_id and rider_id are required")
			return
		}
		if req.Accepted {
			WriteJSON(w, http.StatusOK, map[string]any{
				"status":   "CONFIRMED",
				"order_id": req.OrderID,
				"rider_id": req.RiderID,
			})
			return
		}

		dispatchAndRespond(r.Context(), w, req.toDispatchAssignRequest(), matcher, publisher, locker, logger)
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
	if err := optimizer.ValidatePosition(req.OrderPosition); err != nil {
		return fmt.Errorf("order_position: %w", err)
	}
	if req.StorePosition != nil {
		if err := optimizer.ValidatePosition(*req.StorePosition); err != nil {
			return fmt.Errorf("store_position: %w", err)
		}
	}
	if err := validateNonNegativeFinite("weight", req.Weight); err != nil {
		return err
	}
	if err := validateNonNegativeFinite("volume_liters", req.VolumeLiters); err != nil {
		return err
	}
	for _, rider := range req.AvailableRiders {
		if rider.ID != "" {
			if err := optimizer.ValidatePosition(rider.Position); err != nil {
				return fmt.Errorf("rider %s: %w", rider.ID, err)
			}
		}
	}
	return nil
}

func validateNonNegativeFinite(field string, val float64) error {
	if math.IsNaN(val) || math.IsInf(val, 0) || val < 0 {
		return fmt.Errorf("%s must be finite and non-negative", field)
	}
	return nil
}
