package handler

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"time"

	"github.com/instacommerce/dispatch-optimizer-service/optimizer"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
)

// DispatchAssignRequest is the request payload for POST /dispatch/assign.
type DispatchAssignRequest struct {
	OrderID         string                 `json:"order_id"`
	StoreID         string                 `json:"store_id"`
	UserID          string                 `json:"user_id,omitempty"`
	DeliveryAddress string                 `json:"delivery_address,omitempty"`
	OrderPosition   optimizer.Position     `json:"order_position"`
	ItemCount       int                    `json:"item_count,omitempty"`
	Weight          float64                `json:"weight,omitempty"`
	SLADeadline     time.Time              `json:"sla_deadline,omitempty"`
	CreatedAt       time.Time              `json:"created_at,omitempty"`
	AvailableRiders []optimizer.RiderState `json:"available_riders"`
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
	Status              string              `json:"status"` // "ASSIGNED", "QUEUED_NO_RIDERS", "ESCALATED"
	RiderID             string              `json:"rider_id,omitempty"`
	OrderID             string              `json:"order_id"`
	EstimatedETAMinutes float64             `json:"estimated_eta_minutes,omitempty"`
	DistanceKm          float64             `json:"distance_km,omitempty"`
	RetryAfterSeconds   int                 `json:"retry_after_seconds,omitempty"`
	Escalated           bool                `json:"escalated"`
	EscalationReason    string              `json:"escalation_reason,omitempty"`
	Event               *RiderAssignedEvent `json:"event,omitempty"`
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

// HandleDispatchAssign exposes POST /dispatch/assign as the canonical dispatch assignment
// authority emitting RiderAssigned.v1.json to rider.events (Issue #571 & Issue #576).
func HandleDispatchAssign(matcher *optimizer.BipartiteMatcher, publisher EventPublisher, logger *slog.Logger) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
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

		matchRes, err := matcher.Match(ctx, req.AvailableRiders, orders)
		if err != nil {
			if errors.Is(err, optimizer.ErrNoRidersAvailable) {
				logger.Warn("dispatch: no riders available within radius",
					"order_id", req.OrderID,
					"store_id", req.StoreID,
					"escalated", matchRes != nil && matchRes.Escalated,
				)
				resp := DispatchAssignResponse{
					Status:            "QUEUED_NO_RIDERS",
					OrderID:           req.OrderID,
					RetryAfterSeconds: int(optimizer.DefaultReevaluationInterval.Seconds()),
					Escalated:         matchRes != nil && matchRes.Escalated,
					EscalationReason:  matchRes.EscalationReason,
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

		if len(matchRes.Assignments) == 0 || len(matchRes.Assignments[0].OrderIDs) == 0 {
			writeV2JSON(w, http.StatusOK, DispatchAssignResponse{
				Status:            "QUEUED_NO_RIDERS",
				OrderID:           req.OrderID,
				RetryAfterSeconds: int(optimizer.DefaultReevaluationInterval.Seconds()),
				Escalated:         matchRes.Escalated,
				EscalationReason:  matchRes.EscalationReason,
			})
			return
		}

		assignment := matchRes.Assignments[0]
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
		)

		writeV2JSON(w, http.StatusOK, DispatchAssignResponse{
			Status:              "ASSIGNED",
			RiderID:             assignment.RiderID,
			OrderID:             req.OrderID,
			EstimatedETAMinutes: assignment.EstimatedETAMinutes,
			DistanceKm:          assignment.TotalDistanceKm,
			Event:               assignedEvent,
		})
	}
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
