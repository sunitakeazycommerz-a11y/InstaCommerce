package api

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestHandleListRunsMethodValidation(t *testing.T) {
	handler := NewReconciliationHandler(nil, nil)

	// POST should be rejected with 405
	req := httptest.NewRequest(http.MethodPost, "/runs", nil)
	rec := httptest.NewRecorder()

	handler.HandleListRuns(rec, req)

	assert.Equal(t, http.StatusMethodNotAllowed, rec.Code)
	var errResp ErrorResponse
	err := json.NewDecoder(rec.Body).Decode(&errResp)
	require.NoError(t, err)
	assert.Equal(t, "method not allowed", errResp.Error)
}

func TestHandleGetRunMismatchesInvalidPathAndMethod(t *testing.T) {
	handler := NewReconciliationHandler(nil, nil)

	// POST method should be rejected
	reqPost := httptest.NewRequest(http.MethodPost, "/runs/42/mismatches", nil)
	recPost := httptest.NewRecorder()
	handler.HandleGetRunMismatches(recPost, reqPost)
	assert.Equal(t, http.StatusMethodNotAllowed, recPost.Code)

	// Invalid short path
	reqInvalid := httptest.NewRequest(http.MethodGet, "/mismatches", nil)
	recInvalid := httptest.NewRecorder()
	handler.HandleGetRunMismatches(recInvalid, reqInvalid)
	assert.Equal(t, http.StatusBadRequest, recInvalid.Code)

	// Non-integer run ID in path
	reqNonInt := httptest.NewRequest(http.MethodGet, "/runs/invalid-id/mismatches", nil)
	recNonInt := httptest.NewRecorder()
	handler.HandleGetRunMismatches(recNonInt, reqNonInt)
	assert.Equal(t, http.StatusBadRequest, recNonInt.Code)
}

func TestHandleReviewMismatchValidation(t *testing.T) {
	handler := NewReconciliationHandler(nil, nil)

	// GET method should be rejected
	reqGet := httptest.NewRequest(http.MethodGet, "/mismatches/100/review", nil)
	recGet := httptest.NewRecorder()
	handler.HandleReviewMismatch(recGet, reqGet)
	assert.Equal(t, http.StatusMethodNotAllowed, recGet.Code)

	// Invalid path
	reqPath := httptest.NewRequest(http.MethodPost, "/review", bytes.NewReader([]byte("{}")))
	recPath := httptest.NewRecorder()
	handler.HandleReviewMismatch(recPath, reqPath)
	assert.Equal(t, http.StatusBadRequest, recPath.Code)

	// Non-integer mismatch ID
	reqID := httptest.NewRequest(http.MethodPost, "/mismatches/abc/review", bytes.NewReader([]byte("{}")))
	recID := httptest.NewRecorder()
	handler.HandleReviewMismatch(recID, reqID)
	assert.Equal(t, http.StatusBadRequest, recID.Code)

	// Malformed JSON
	reqBadJSON := httptest.NewRequest(http.MethodPost, "/mismatches/100/review", bytes.NewReader([]byte("invalid json")))
	recBadJSON := httptest.NewRecorder()
	handler.HandleReviewMismatch(recBadJSON, reqBadJSON)
	assert.Equal(t, http.StatusBadRequest, recBadJSON.Code)
}

func TestHandleApplyFixValidation(t *testing.T) {
	handler := NewReconciliationHandler(nil, nil)

	// GET method should be rejected
	reqGet := httptest.NewRequest(http.MethodGet, "/mismatches/100/fix", nil)
	recGet := httptest.NewRecorder()
	handler.HandleApplyFix(recGet, reqGet)
	assert.Equal(t, http.StatusMethodNotAllowed, recGet.Code)

	// Missing fix_action
	bodyMissingAction, _ := json.Marshal(map[string]string{
		"operator_id": "op-42",
		"notes":       "test fix",
	})
	reqMissingAction := httptest.NewRequest(http.MethodPost, "/mismatches/100/fix", bytes.NewReader(bodyMissingAction))
	recMissingAction := httptest.NewRecorder()
	handler.HandleApplyFix(recMissingAction, reqMissingAction)
	assert.Equal(t, http.StatusBadRequest, recMissingAction.Code)

	var errResp ErrorResponse
	err := json.NewDecoder(recMissingAction.Body).Decode(&errResp)
	require.NoError(t, err)
	assert.Equal(t, "fix_action is required", errResp.Error)
}

func TestWriteJSONAndErrorHelpers(t *testing.T) {
	handler := NewReconciliationHandler(nil, nil)
	rec := httptest.NewRecorder()

	handler.writeError(rec, http.StatusNotFound, "entity not found")
	assert.Equal(t, http.StatusNotFound, rec.Code)
	assert.Equal(t, "application/json", rec.Header().Get("Content-Type"))

	var errResp ErrorResponse
	err := json.NewDecoder(rec.Body).Decode(&errResp)
	require.NoError(t, err)
	assert.Equal(t, "entity not found", errResp.Error)
}
