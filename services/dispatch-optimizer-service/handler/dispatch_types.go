package handler

import (
	"context"
	"log/slog"
	"time"

	"github.com/instacommerce/dispatch-optimizer-service/optimizer"
)

const (
	DefaultRiderLockTTL                  = 30 * time.Second
	DefaultRiderAcceptanceTimeoutSeconds = 45
	MaxDispatchAttemptsBeforeAlert       = 3
)

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
	VolumeLiters             float64                `json:"volume_liters,omitempty"`
	IsExpressOrder           bool                   `json:"is_express_order,omitempty"`
	RequiresColdChain        bool                   `json:"requires_cold_chain,omitempty"`
	ColdChainTier            string                 `json:"cold_chain_tier,omitempty"`
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

// RiderAssignedEvent conforms to schemas/rider/RiderAssigned.v1.json.
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
	Status                   string              `json:"status"`
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
	OrderID           string                 `json:"order_id"`
	StoreID           string                 `json:"store_id"`
	UserID            string                 `json:"user_id,omitempty"`
	DeliveryAddress   string                 `json:"delivery_address,omitempty"`
	RiderID           string                 `json:"rider_id"`
	Accepted          bool                   `json:"accepted"`
	Reason            string                 `json:"reason,omitempty"`
	StorePosition     *optimizer.Position    `json:"store_position,omitempty"`
	OrderPosition     optimizer.Position     `json:"order_position"`
	ItemCount         int                    `json:"item_count,omitempty"`
	Weight            float64                `json:"weight,omitempty"`
	VolumeLiters      float64                `json:"volume_liters,omitempty"`
	IsExpressOrder    bool                   `json:"is_express_order,omitempty"`
	RequiresColdChain bool                   `json:"requires_cold_chain,omitempty"`
	ColdChainTier     string                 `json:"cold_chain_tier,omitempty"`
	SLADeadline       time.Time              `json:"sla_deadline,omitempty"`
	CreatedAt         time.Time              `json:"created_at,omitempty"`
	AttemptCount      int                    `json:"attempt_count"`
	ExcludedRiderIDs  []string               `json:"excluded_rider_ids"`
	AvailableRiders   []optimizer.RiderState `json:"available_riders"`
}

func (r RiderResponseRequest) toDispatchAssignRequest() DispatchAssignRequest {
	excluded := make([]string, len(r.ExcludedRiderIDs), len(r.ExcludedRiderIDs)+1)
	copy(excluded, r.ExcludedRiderIDs)
	excluded = append(excluded, r.RiderID)

	return DispatchAssignRequest{
		OrderID:                  r.OrderID,
		StoreID:                  r.StoreID,
		UserID:                   r.UserID,
		DeliveryAddress:          r.DeliveryAddress,
		StorePosition:            r.StorePosition,
		OrderPosition:            r.OrderPosition,
		ItemCount:                r.ItemCount,
		Weight:                   r.Weight,
		VolumeLiters:             r.VolumeLiters,
		IsExpressOrder:           r.IsExpressOrder,
		RequiresColdChain:        r.RequiresColdChain,
		ColdChainTier:            r.ColdChainTier,
		SLADeadline:              r.SLADeadline,
		CreatedAt:                r.CreatedAt,
		AvailableRiders:          r.AvailableRiders,
		AttemptCount:             r.AttemptCount,
		ExcludedRiderIDs:         excluded,
		AcceptanceTimeoutSeconds: DefaultRiderAcceptanceTimeoutSeconds,
	}
}

// EventPublisher emits canonical Kafka events.
type EventPublisher interface {
	Publish(ctx context.Context, topic string, key string, payload any) error
}

// NoopEventPublisher logs emitted events without external broker dependencies.
type NoopEventPublisher struct {
	Logger *slog.Logger
}

func (p *NoopEventPublisher) Publish(_ context.Context, topic string, key string, payload any) error {
	if p.Logger != nil {
		p.Logger.Info("published event", "topic", topic, "key", key, "payload", payload)
	}
	return nil
}
