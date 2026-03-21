// Package api provides HTTP handlers for the reverse-etl-orchestrator REST API.
package api

import (
	"encoding/json"
	"log/slog"
	"net/http"

	"github.com/gorilla/mux"

	"github.com/instacommerce/reverse-etl-orchestrator/internal/subscription"
)

// Handler provides HTTP handlers for the API.
type Handler struct {
	subscriptionSvc *subscription.Service
}

// NewHandler creates a new API handler.
func NewHandler(subscriptionSvc *subscription.Service) *Handler {
	return &Handler{
		subscriptionSvc: subscriptionSvc,
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
	json.NewEncoder(w).Encode(data)
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

// CreateSubscription handles POST /api/v1/subscriptions.
func (h *Handler) CreateSubscription(w http.ResponseWriter, r *http.Request) {
	var req subscription.CreateRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_request", "Invalid JSON payload")
		return
	}

	// Get user from context (would be set by auth middleware)
	createdBy := r.Header.Get("X-User-Email")
	if createdBy == "" {
		createdBy = "system"
	}

	sub, err := h.subscriptionSvc.Create(r.Context(), req, createdBy)
	if err != nil {
		slog.Error("failed to create subscription", "error", err)
		writeError(w, http.StatusInternalServerError, "internal_error", "Failed to create subscription")
		return
	}

	writeJSON(w, http.StatusCreated, sub)
}

// GetSubscription handles GET /api/v1/subscriptions/{id}.
func (h *Handler) GetSubscription(w http.ResponseWriter, r *http.Request) {
	vars := mux.Vars(r)
	id := vars["id"]

	sub, err := h.subscriptionSvc.Get(r.Context(), id)
	if err != nil {
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
		slog.Error("failed to archive subscription", "error", err, "id", id)
		writeError(w, http.StatusInternalServerError, "internal_error", "Failed to archive subscription")
		return
	}

	w.WriteHeader(http.StatusNoContent)
}

// ActivateSubscription handles POST /api/v1/subscriptions/{id}/activate.
func (h *Handler) ActivateSubscription(w http.ResponseWriter, r *http.Request) {
	vars := mux.Vars(r)
	id := vars["id"]

	if err := h.subscriptionSvc.Activate(r.Context(), id); err != nil {
		slog.Error("failed to activate subscription", "error", err, "id", id)
		writeError(w, http.StatusInternalServerError, "internal_error", "Failed to activate subscription")
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
		slog.Error("failed to pause subscription", "error", err, "id", id)
		writeError(w, http.StatusInternalServerError, "internal_error", "Failed to pause subscription")
		return
	}

	writeJSON(w, http.StatusOK, map[string]string{
		"status":  "paused",
		"message": "Subscription paused successfully",
	})
}
