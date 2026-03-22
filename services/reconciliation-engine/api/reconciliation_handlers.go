package api

import (
	"encoding/json"
	"log/slog"
	"net/http"
	"strconv"
	"strings"

	"github.com/instacommerce/reconciliation-engine/pkg/reconciliation"
)

type ReconciliationHandler struct {
	reconciler *reconciliation.DBReconciler
	logger     *slog.Logger
}

func NewReconciliationHandler(reconciler *reconciliation.DBReconciler, logger *slog.Logger) *ReconciliationHandler {
	return &ReconciliationHandler{
		reconciler: reconciler,
		logger:     logger,
	}
}

type ListRunsResponse struct {
	Runs  []ReconciliationRunDTO `json:"runs"`
	Count int                    `json:"count"`
}

type ReconciliationRunDTO struct {
	RunID             int64   `json:"run_id"`
	RunDate           string  `json:"run_date"`
	Status            string  `json:"status"`
	MismatchCount     int     `json:"mismatch_count"`
	AutoFixedCount    int     `json:"auto_fixed_count"`
	ManualReviewCount int     `json:"manual_review_count"`
	StartedAt         *string `json:"started_at,omitempty"`
	CompletedAt       *string `json:"completed_at,omitempty"`
}

type ListMismatchesResponse struct {
	Mismatches []MismatchDTO `json:"mismatches"`
	Count      int           `json:"count"`
}

type MismatchDTO struct {
	MismatchID          int64   `json:"mismatch_id"`
	TransactionID       string  `json:"transaction_id"`
	LedgerAmount        string  `json:"ledger_amount"`
	PSPAmount           string  `json:"psp_amount"`
	DiscrepancyAmount   string  `json:"discrepancy_amount"`
	DiscrepancyReason   string  `json:"discrepancy_reason"`
	AutoFixed           bool    `json:"auto_fixed"`
	ManualReviewRequired bool    `json:"manual_review_required"`
	FixAppliedAt        *string `json:"fix_applied_at,omitempty"`
}

type ErrorResponse struct {
	Error   string `json:"error"`
	Details string `json:"details,omitempty"`
}

func (h *ReconciliationHandler) HandleListRuns(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		h.writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}

	limit := 50
	offset := 0

	if limitStr := r.URL.Query().Get("limit"); limitStr != "" {
		if l, err := strconv.Atoi(limitStr); err == nil && l > 0 && l <= 1000 {
			limit = l
		}
	}

	if offsetStr := r.URL.Query().Get("offset"); offsetStr != "" {
		if o, err := strconv.Atoi(offsetStr); err == nil && o >= 0 {
			offset = o
		}
	}

	runs, err := h.reconciler.GetReconciliationRuns(r.Context(), limit, offset)
	if err != nil {
		h.logger.Error("failed to get reconciliation runs", "error", err)
		h.writeError(w, http.StatusInternalServerError, "failed to retrieve runs")
		return
	}

	dtos := make([]ReconciliationRunDTO, len(runs))
	for i, run := range runs {
		dtos[i] = ReconciliationRunDTO{
			RunID:             run.RunID,
			RunDate:           run.RunDate.Format("2006-01-02"),
			Status:            run.Status,
			MismatchCount:     run.MismatchCount,
			AutoFixedCount:    run.AutoFixedCount,
			ManualReviewCount: run.ManualReviewCount,
			StartedAt:         formatTimestamp(run.StartedAt),
			CompletedAt:       formatTimestamp(run.CompletedAt),
		}
	}

	h.writeJSON(w, http.StatusOK, ListRunsResponse{
		Runs:  dtos,
		Count: len(dtos),
	})
}

func (h *ReconciliationHandler) HandleGetRunMismatches(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		h.writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}

	parts := strings.Split(r.URL.Path, "/")
	if len(parts) < 3 {
		h.writeError(w, http.StatusBadRequest, "invalid path")
		return
	}

	runID, err := strconv.ParseInt(parts[len(parts)-3], 10, 64)
	if err != nil {
		h.writeError(w, http.StatusBadRequest, "invalid run ID")
		return
	}

	mismatches, err := h.reconciler.GetMismatchesForRun(r.Context(), runID)
	if err != nil {
		h.logger.Error("failed to get mismatches", "error", err, "run_id", runID)
		h.writeError(w, http.StatusInternalServerError, "failed to retrieve mismatches")
		return
	}

	dtos := make([]MismatchDTO, len(mismatches))
	for i, m := range mismatches {
		dtos[i] = MismatchDTO{
			MismatchID:          m.MismatchID,
			TransactionID:       m.TransactionID,
			LedgerAmount:        m.LedgerAmount,
			PSPAmount:           m.PSPAmount,
			DiscrepancyAmount:   m.DiscrepancyAmount,
			DiscrepancyReason:   m.DiscrepancyReason,
			AutoFixed:           m.AutoFixed,
			ManualReviewRequired: m.ManualReviewRequired,
			FixAppliedAt:        formatTimestamp(m.FixAppliedAt),
		}
	}

	h.writeJSON(w, http.StatusOK, ListMismatchesResponse{
		Mismatches: dtos,
		Count:      len(dtos),
	})
}

func (h *ReconciliationHandler) HandleReviewMismatch(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		h.writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}

	parts := strings.Split(r.URL.Path, "/")
	if len(parts) < 3 {
		h.writeError(w, http.StatusBadRequest, "invalid path")
		return
	}

	mismatchID, err := strconv.ParseInt(parts[len(parts)-3], 10, 64)
	if err != nil {
		h.writeError(w, http.StatusBadRequest, "invalid mismatch ID")
		return
	}

	var req reconciliation.ReviewRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		h.writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	if err := h.reconciler.ReviewMismatch(r.Context(), mismatchID, req.ManualReviewRequired); err != nil {
		h.logger.Error("failed to review mismatch", "error", err, "mismatch_id", mismatchID)
		h.writeError(w, http.StatusInternalServerError, "failed to review mismatch")
		return
	}

	h.writeJSON(w, http.StatusOK, map[string]string{"status": "reviewed"})
}

func (h *ReconciliationHandler) HandleApplyFix(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		h.writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}

	parts := strings.Split(r.URL.Path, "/")
	if len(parts) < 3 {
		h.writeError(w, http.StatusBadRequest, "invalid path")
		return
	}

	mismatchID, err := strconv.ParseInt(parts[len(parts)-3], 10, 64)
	if err != nil {
		h.writeError(w, http.StatusBadRequest, "invalid mismatch ID")
		return
	}

	var req reconciliation.FixRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		h.writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	if strings.TrimSpace(req.FixAction) == "" {
		h.writeError(w, http.StatusBadRequest, "fix_action is required")
		return
	}

	if err := h.reconciler.ApplyFix(r.Context(), mismatchID, req.FixAction, req.OperatorID, req.Notes); err != nil {
		h.logger.Error("failed to apply fix", "error", err, "mismatch_id", mismatchID)
		h.writeError(w, http.StatusInternalServerError, "failed to apply fix")
		return
	}

	h.writeJSON(w, http.StatusOK, map[string]string{"status": "fix_applied"})
}

func (h *ReconciliationHandler) writeJSON(w http.ResponseWriter, status int, payload interface{}) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	encoder := json.NewEncoder(w)
	encoder.SetEscapeHTML(false)
	_ = encoder.Encode(payload)
}

func (h *ReconciliationHandler) writeError(w http.ResponseWriter, status int, message string) {
	h.writeJSON(w, status, ErrorResponse{Error: message})
}

func formatTimestamp(t *interface{}) *string {
	if t == nil {
		return nil
	}
	ts := (*t).(string)
	return &ts
}
