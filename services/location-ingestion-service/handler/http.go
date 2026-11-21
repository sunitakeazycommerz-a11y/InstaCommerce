package handler

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"net/http"
)

// HandleHTTP accepts a single JSON LocationUpdate via POST.
func (h *LocationHandler) HandleHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", "POST")
		h.writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}

	h.recordReceived("http")

	maxBytes := h.cfg.MaxBodyBytes
	if maxBytes <= 0 {
		maxBytes = maxLocationHandlerBody
	}
	r.Body = http.MaxBytesReader(w, r.Body, maxBytes)
	defer r.Body.Close()

	body, err := io.ReadAll(r.Body)
	if err != nil {
		h.recordInvalid("http", "body_read")
		var maxBytesErr *http.MaxBytesError
		if errors.As(err, &maxBytesErr) {
			h.writeError(w, http.StatusRequestEntityTooLarge, "request body too large")
			return
		}
		h.writeError(w, http.StatusBadRequest, "failed to read request body")
		return
	}

	var update LocationUpdate
	if err := json.Unmarshal(body, &update); err != nil {
		h.recordInvalid("http", "decode")
		h.writeError(w, http.StatusBadRequest, "invalid JSON payload")
		return
	}

	if err := h.processUpdate(r.Context(), update, "http"); err != nil {
		if isOutOfOrderErr(err) {
			h.writeJSON(w, http.StatusOK, map[string]string{
				"status": "ignored",
				"reason": "out_of_order_timestamp",
			})
			return
		}
		h.writeError(w, http.StatusUnprocessableEntity, err.Error())
		return
	}

	h.writeJSON(w, http.StatusAccepted, map[string]string{"status": "accepted"})
}

// HandleIngest accepts a single strict JSON LocationInput via POST.
func (h *LocationHandler) HandleIngest(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", "POST")
		h.writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}

	h.recordReceived("http")

	maxBytes := h.cfg.MaxBodyBytes
	if maxBytes <= 0 {
		maxBytes = defaultMaxBodyBytes
	}
	r.Body = http.MaxBytesReader(w, r.Body, maxBytes)
	defer r.Body.Close()
	decoder := json.NewDecoder(r.Body)
	decoder.DisallowUnknownFields()

	var input LocationInput
	if err := decoder.Decode(&input); err != nil {
		h.recordDropped("http", "decode")
		var maxBytesErr *http.MaxBytesError
		if errors.As(err, &maxBytesErr) {
			h.writeError(w, http.StatusRequestEntityTooLarge, "request body too large")
			return
		}
		h.writeError(w, http.StatusBadRequest, "invalid JSON payload")
		return
	}
	if err := decoder.Decode(&struct{}{}); err != io.EOF {
		h.recordDropped("http", "decode")
		h.writeError(w, http.StatusBadRequest, "payload must contain a single JSON object")
		return
	}

	if err := h.ProcessInput(r.Context(), input, "http"); err != nil {
		if isOutOfOrderErr(err) {
			h.writeJSON(w, http.StatusOK, map[string]string{
				"status": "ignored",
				"reason": "out_of_order_timestamp",
			})
			return
		}
		status := http.StatusServiceUnavailable
		if errors.Is(err, ErrInvalidPayload) {
			status = http.StatusBadRequest
		} else if errors.Is(err, ErrVelocityOutlier) || errors.Is(err, ErrTeleportAnomaly) {
			status = http.StatusUnprocessableEntity
		}
		h.writeError(w, status, err.Error())
		return
	}

	h.writeJSON(w, http.StatusAccepted, map[string]string{"status": "accepted"})
}

// HandleBatchIngest accepts a batch of LocationInput updates.
func (h *LocationHandler) HandleBatchIngest(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", "POST")
		h.writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}

	maxBytes := h.cfg.MaxBodyBytes
	if maxBytes <= 0 {
		maxBytes = defaultMaxBodyBytes
	}
	r.Body = http.MaxBytesReader(w, r.Body, maxBytes)
	defer r.Body.Close()
	body, err := io.ReadAll(r.Body)
	if err != nil {
		h.recordDropped("http", "decode")
		var maxBytesErr *http.MaxBytesError
		if errors.As(err, &maxBytesErr) {
			h.writeError(w, http.StatusRequestEntityTooLarge, "request body too large")
			return
		}
		h.writeError(w, http.StatusBadRequest, "failed to read request body")
		return
	}

	var inputs []LocationInput
	trimmed := bytes.TrimSpace(body)
	if len(trimmed) > 0 && trimmed[0] == '[' {
		if err := json.Unmarshal(trimmed, &inputs); err != nil {
			h.recordDropped("http", "decode")
			h.writeError(w, http.StatusBadRequest, "invalid JSON array payload")
			return
		}
	} else {
		var wrapper struct {
			Updates []LocationInput `json:"updates"`
		}
		if err := json.Unmarshal(trimmed, &wrapper); err != nil {
			h.recordDropped("http", "decode")
			h.writeError(w, http.StatusBadRequest, "invalid JSON batch payload")
			return
		}
		inputs = wrapper.Updates
	}

	if len(inputs) == 0 {
		h.writeError(w, http.StatusBadRequest, "batch must contain at least one update")
		return
	}

	accepted, ignored, failed := 0, 0, 0
	for _, input := range inputs {
		h.recordReceived("http")
		if err := h.ProcessInput(r.Context(), input, "http"); err != nil {
			if isOutOfOrderErr(err) {
				ignored++
				continue
			}
			failed++
			continue
		}
		accepted++
	}

	h.writeJSON(w, http.StatusAccepted, map[string]any{
		"status":   "accepted",
		"accepted": accepted,
		"ignored":  ignored,
		"failed":   failed,
	})
}

func (h *LocationHandler) writeJSON(w http.ResponseWriter, status int, payload any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	enc := json.NewEncoder(w)
	enc.SetEscapeHTML(false)
	_ = enc.Encode(payload)
}

func (h *LocationHandler) writeError(w http.ResponseWriter, status int, message string) {
	h.writeJSON(w, status, map[string]string{"error": message})
}
