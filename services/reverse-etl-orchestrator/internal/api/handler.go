// Package api provides HTTP handlers for the reverse-etl-orchestrator REST API.
package api

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"strings"

	"github.com/google/uuid"
	"github.com/gorilla/mux"

	"github.com/instacommerce/reverse-etl-orchestrator/internal/subscription"
)

// SubscriptionManager defines operations required by the API handler.
type SubscriptionManager interface {
	Create(ctx context.Context, req subscription.CreateRequest, createdBy string) (*subscription.Subscription, error)
	Get(ctx context.Context, id string) (*subscription.Subscription, error)
	List(ctx context.Context, sourceDomain, sinkType string, state subscription.State) ([]*subscription.Subscription, error)
	Activate(ctx context.Context, id string) error
	Pause(ctx context.Context, id string) error
	Archive(ctx context.Context, id string) error
}

// SyncRunner executes reverse ETL sync workloads via WorkerPool.
type SyncRunner interface {
	SyncUserFeatures(ctx context.Context, jobID, subscriptionID string, records []subscription.UserFeatureRecord) (subscription.SyncResult, error)
	SyncCustomerFraudScores(ctx context.Context, jobID, subscriptionID string, scores []subscription.CustomerFraudScoreRecord) (subscription.SyncResult, error)
	SyncDarkStoreInventoryParallel(ctx context.Context, jobID, subscriptionID string, storeIDs []string, fetchStoreInventory func(ctx context.Context, storeID string) ([]subscription.InventoryRecord, error)) (subscription.SyncResult, error)
}

// SyncRequest specifies an optional sync trigger payload for POST /api/v1/subscriptions/{id}/sync.
type SyncRequest struct {
	JobID            string                                    `json:"job_id,omitempty"`
	SyncType         string                                    `json:"sync_type,omitempty"`
	UserFeatures     []subscription.UserFeatureRecord          `json:"user_features,omitempty"`
	FraudRiskScores  []subscription.CustomerFraudScoreRecord   `json:"fraud_risk_scores,omitempty"`
	StoreIDs         []string                                  `json:"store_ids,omitempty"`
	InventoryByStore map[string][]subscription.InventoryRecord `json:"inventory_by_store,omitempty"`
}

// Handler provides HTTP handlers for the API.
type Handler struct {
	subscriptionSvc SubscriptionManager
	syncRunner      SyncRunner
}

// NewHandler creates a new API handler.
func NewHandler(subscriptionSvc SubscriptionManager) *Handler {
	return &Handler{
		subscriptionSvc: subscriptionSvc,
	}
}

// NewHandlerWithSync creates a Handler wired to a SyncRunner (such as WorkerPool).
func NewHandlerWithSync(subscriptionSvc SubscriptionManager, syncRunner SyncRunner) *Handler {
	return &Handler{
		subscriptionSvc: subscriptionSvc,
		syncRunner:      syncRunner,
	}
}

// RegisterRoutes registers all API routes.
func (h *Handler) RegisterRoutes(r *mux.Router) {
	// Subscriptions
	r.HandleFunc("/subscriptions", h.ListSubscriptions).Methods("GET")
	r.HandleFunc("/subscriptions", h.CreateSubscription).Methods("POST")
	r.HandleFunc("/subscriptions/{id}", h.GetSubscription).Methods("GET")
	r.HandleFunc("/subscriptions/{id}", h.DeleteSubscription).Methods("DELETE")
	r.HandleFunc("/subscriptions/{id}/activate", h.ActivateSubscription).Methods("POST")
	r.HandleFunc("/subscriptions/{id}/pause", h.PauseSubscription).Methods("POST")
	r.HandleFunc("/subscriptions/{id}/sync", h.TriggerSync).Methods("POST")

	// Health check at API level
	r.HandleFunc("/health", h.Health).Methods("GET")
}

// ErrorResponse represents an error response.
type ErrorResponse struct {
	Error   string `json:"error"`
	Message string `json:"message"`
}

func writeJSON(w http.ResponseWriter, status int, data interface{}) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	if err := json.NewEncoder(w).Encode(data); err != nil {
		slog.Error("failed to encode JSON response", "error", err)
	}
}

func writeError(w http.ResponseWriter, status int, err string, message string) {
	writeJSON(w, status, ErrorResponse{Error: err, Message: message})
}

// Health handles GET /api/v1/health.
func (h *Handler) Health(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{"status": "healthy"})
}

// ListSubscriptions handles GET /api/v1/subscriptions.
func (h *Handler) ListSubscriptions(w http.ResponseWriter, r *http.Request) {
	sourceDomain := r.URL.Query().Get("source_domain")
	sinkType := r.URL.Query().Get("sink_type")
	state := subscription.State(r.URL.Query().Get("state"))

	subs, err := h.subscriptionSvc.List(r.Context(), sourceDomain, sinkType, state)
	if err != nil {
		slog.Error("failed to list subscriptions", "error", err)
		writeError(w, http.StatusInternalServerError, "internal_error", "Failed to list subscriptions")
		return
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"subscriptions": subs,
		"total":         len(subs),
	})
}

func isNotFoundError(err error) bool {
	if err == nil {
		return false
	}
	return errors.Is(err, subscription.ErrSubscriptionNotFound) ||
		strings.Contains(strings.ToLower(err.Error()), "not found")
}

func writeTransitionError(w http.ResponseWriter, err error, action, id string) {
	if isNotFoundError(err) {
		writeError(w, http.StatusNotFound, "not_found", "Subscription not found")
		return
	}
	if errors.Is(err, subscription.ErrInvalidStateTransition) {
		writeError(w, http.StatusConflict, "invalid_state_transition", err.Error())
		return
	}
	slog.Error("failed subscription operation", "action", action, "error", err, "id", id)
	writeError(w, http.StatusInternalServerError, "internal_error", "Failed to "+action+" subscription")
}

// CreateSubscription handles POST /api/v1/subscriptions.
func (h *Handler) CreateSubscription(w http.ResponseWriter, r *http.Request) {
	r.Body = http.MaxBytesReader(w, r.Body, 1<<20)
	defer r.Body.Close()

	var req subscription.CreateRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_request", "Invalid JSON payload")
		return
	}
	if strings.TrimSpace(req.Name) == "" || strings.TrimSpace(req.SourceDomain) == "" || strings.TrimSpace(req.SinkType) == "" {
		writeError(w, http.StatusBadRequest, "validation_error", "name, source_domain, and sink_type are required")
		return
	}

	createdBy := resolveAuditActor(r)

	sub, err := h.subscriptionSvc.Create(r.Context(), req, createdBy)
	if err != nil {
		if errors.Is(err, subscription.ErrInvalidSubscription) {
			writeError(w, http.StatusBadRequest, "validation_error", err.Error())
			return
		}
		slog.Error("failed to create subscription", "error", err)
		writeError(w, http.StatusInternalServerError, "internal_error", "Failed to create subscription")
		return
	}

	writeJSON(w, http.StatusCreated, sub)
}

func resolveAuditActor(r *http.Request) string {
	if email := strings.TrimSpace(r.Header.Get("X-User-Email")); email != "" {
		return email
	}
	if svc := strings.TrimSpace(r.Header.Get("X-Internal-Service")); svc != "" {
		return svc
	}
	return "system"
}

// GetSubscription handles GET /api/v1/subscriptions/{id}.
func (h *Handler) GetSubscription(w http.ResponseWriter, r *http.Request) {
	vars := mux.Vars(r)
	id := vars["id"]

	sub, err := h.subscriptionSvc.Get(r.Context(), id)
	if err != nil {
		if isNotFoundError(err) {
			writeError(w, http.StatusNotFound, "not_found", "Subscription not found")
			return
		}
		slog.Error("failed to get subscription", "error", err, "id", id)
		writeError(w, http.StatusInternalServerError, "internal_error", "Failed to get subscription")
		return
	}

	if sub == nil {
		writeError(w, http.StatusNotFound, "not_found", "Subscription not found")
		return
	}

	writeJSON(w, http.StatusOK, sub)
}

// DeleteSubscription handles DELETE /api/v1/subscriptions/{id}.
func (h *Handler) DeleteSubscription(w http.ResponseWriter, r *http.Request) {
	vars := mux.Vars(r)
	id := vars["id"]

	if err := h.subscriptionSvc.Archive(r.Context(), id); err != nil {
		writeTransitionError(w, err, "archive", id)
		return
	}

	w.WriteHeader(http.StatusNoContent)
}

// ActivateSubscription handles POST /api/v1/subscriptions/{id}/activate.
func (h *Handler) ActivateSubscription(w http.ResponseWriter, r *http.Request) {
	vars := mux.Vars(r)
	id := vars["id"]

	if err := h.subscriptionSvc.Activate(r.Context(), id); err != nil {
		writeTransitionError(w, err, "activate", id)
		return
	}

	writeJSON(w, http.StatusOK, map[string]string{
		"status":  "activated",
		"message": "Subscription activated successfully",
	})
}

// PauseSubscription handles POST /api/v1/subscriptions/{id}/pause.
func (h *Handler) PauseSubscription(w http.ResponseWriter, r *http.Request) {
	vars := mux.Vars(r)
	id := vars["id"]

	if err := h.subscriptionSvc.Pause(r.Context(), id); err != nil {
		writeTransitionError(w, err, "pause", id)
		return
	}

	writeJSON(w, http.StatusOK, map[string]string{
		"status":  "paused",
		"message": "Subscription paused successfully",
	})
}

// TriggerSync handles POST /api/v1/subscriptions/{id}/sync by dispatching work to SyncRunner.
func (h *Handler) TriggerSync(w http.ResponseWriter, r *http.Request) {
	id := mux.Vars(r)["id"]
	sub, err := h.subscriptionSvc.Get(r.Context(), id)
	if err != nil {
		if isNotFoundError(err) {
			writeError(w, http.StatusNotFound, "not_found", "Subscription not found")
			return
		}
		slog.Error("failed to load subscription for sync", "error", err, "id", id)
		writeError(w, http.StatusInternalServerError, "internal_error", "Failed to load subscription")
		return
	}
	if sub == nil {
		writeError(w, http.StatusNotFound, "not_found", "Subscription not found")
		return
	}
	if sub.State != subscription.StateActive {
		writeError(w, http.StatusConflict, "subscription_not_active", "Subscription must be ACTIVE to trigger sync")
		return
	}
	if h.syncRunner == nil {
		writeError(w, http.StatusServiceUnavailable, "sync_unavailable", "Sync worker pool is not configured")
		return
	}

	r.Body = http.MaxBytesReader(w, r.Body, 4<<20)
	defer r.Body.Close()

	var req SyncRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil && !errors.Is(err, io.EOF) {
		writeError(w, http.StatusBadRequest, "invalid_request", "Invalid JSON sync payload")
		return
	}

	jobID := strings.TrimSpace(req.JobID)
	if jobID == "" {
		jobID = uuid.New().String()
	}

	syncType := resolveSyncType(req.SyncType, sub)
	var result subscription.SyncResult
	var syncErr error

	switch syncType {
	case "user_features":
		result, syncErr = h.syncRunner.SyncUserFeatures(r.Context(), jobID, id, req.UserFeatures)
	case "fraud_risk_scores":
		result, syncErr = h.syncRunner.SyncCustomerFraudScores(r.Context(), jobID, id, req.FraudRiskScores)
	case "dark_store_inventory":
		storeIDs := req.StoreIDs
		if len(storeIDs) == 0 && len(req.InventoryByStore) > 0 {
			storeIDs = make([]string, 0, len(req.InventoryByStore))
			for storeID := range req.InventoryByStore {
				storeIDs = append(storeIDs, storeID)
			}
		}
		result, syncErr = h.syncRunner.SyncDarkStoreInventoryParallel(r.Context(), jobID, id, storeIDs, func(_ context.Context, storeID string) ([]subscription.InventoryRecord, error) {
			return req.InventoryByStore[storeID], nil
		})
	default:
		writeError(w, http.StatusBadRequest, "unsupported_sync_type", "Unsupported sync_type: "+syncType)
		return
	}

	if syncErr != nil {
		slog.Error("reverse ETL sync failed", "subscription_id", id, "job_id", jobID, "sync_type", syncType, "error", syncErr)
		statusCode := http.StatusInternalServerError
		if strings.Contains(syncErr.Error(), "not configured") {
			statusCode = http.StatusServiceUnavailable
		}
		writeJSON(w, statusCode, map[string]any{
			"error":           "sync_failed",
			"message":         syncErr.Error(),
			"job_id":          jobID,
			"subscription_id": id,
			"sync_type":       syncType,
			"status":          result.Status,
			"rows_processed":  result.RowsProcessed,
			"duration_ms":     result.Duration.Milliseconds(),
		})
		return
	}

	writeJSON(w, http.StatusAccepted, map[string]any{
		"job_id":          result.JobID,
		"subscription_id": id,
		"sync_type":       result.SyncType,
		"rows_processed":  result.RowsProcessed,
		"stores_synced":   result.StoresSynced,
		"status":          result.Status,
		"duration_ms":     result.Duration.Milliseconds(),
	})
}

func resolveSyncType(explicit string, sub *subscription.Subscription) string {
	candidate := strings.ToLower(strings.TrimSpace(explicit))
	if candidate == "" && sub != nil {
		candidate = strings.ToLower(strings.TrimSpace(sub.SourceDomain))
	}
	switch candidate {
	case "user_features", "features", "customer_features", "clv":
		return "user_features"
	case "fraud", "fraud_risk_scores", "fraud_scores":
		return "fraud_risk_scores"
	case "inventory", "dark_store_inventory", "store_inventory":
		return "dark_store_inventory"
	default:
		return candidate
	}
}
