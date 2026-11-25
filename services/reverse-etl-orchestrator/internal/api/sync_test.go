package api

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/gorilla/mux"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/instacommerce/reverse-etl-orchestrator/internal/subscription"
)

type stubSubManager struct {
	sub *subscription.Subscription
	err error
}

func (s *stubSubManager) Create(_ context.Context, _ subscription.CreateRequest, _ string) (*subscription.Subscription, error) {
	return s.sub, s.err
}
func (s *stubSubManager) Get(_ context.Context, _ string) (*subscription.Subscription, error) {
	return s.sub, s.err
}
func (s *stubSubManager) List(_ context.Context, _, _ string, _ subscription.State) ([]*subscription.Subscription, error) {
	return nil, s.err
}
func (s *stubSubManager) Activate(_ context.Context, _ string) error { return s.err }
func (s *stubSubManager) Pause(_ context.Context, _ string) error    { return s.err }
func (s *stubSubManager) Archive(_ context.Context, _ string) error  { return s.err }

type stubSyncRunner struct {
	calledType  string
	lastSubID   string
	lastJobID   string
	featureRows int
	fraudRows   int
	storeIDs    []string
	result      subscription.SyncResult
	err         error
}

func (r *stubSyncRunner) SyncUserFeatures(_ context.Context, jobID, subscriptionID string, records []subscription.UserFeatureRecord) (subscription.SyncResult, error) {
	r.calledType = "user_features"
	r.lastJobID = jobID
	r.lastSubID = subscriptionID
	r.featureRows = len(records)
	if r.err != nil {
		return r.result, r.err
	}
	res := r.result
	res.JobID = jobID
	res.SyncType = "user_features"
	if res.Status == "" {
		res.Status = "COMPLETED"
	}
	return res, nil
}

func (r *stubSyncRunner) SyncCustomerFraudScores(_ context.Context, jobID, subscriptionID string, scores []subscription.CustomerFraudScoreRecord) (subscription.SyncResult, error) {
	r.calledType = "fraud_risk_scores"
	r.lastJobID = jobID
	r.lastSubID = subscriptionID
	r.fraudRows = len(scores)
	if r.err != nil {
		return r.result, r.err
	}
	res := r.result
	res.JobID = jobID
	res.SyncType = "fraud_risk_scores"
	if res.Status == "" {
		res.Status = "COMPLETED"
	}
	return res, nil
}

func (r *stubSyncRunner) SyncDarkStoreInventoryParallel(ctx context.Context, jobID, subscriptionID string, storeIDs []string, fetch func(context.Context, string) ([]subscription.InventoryRecord, error)) (subscription.SyncResult, error) {
	r.calledType = "dark_store_inventory"
	r.lastJobID = jobID
	r.lastSubID = subscriptionID
	r.storeIDs = append([]string(nil), storeIDs...)
	for _, sid := range storeIDs {
		if _, err := fetch(ctx, sid); err != nil {
			return subscription.SyncResult{Status: "FAILED"}, err
		}
	}
	if r.err != nil {
		return r.result, r.err
	}
	res := r.result
	res.JobID = jobID
	res.SyncType = "dark_store_inventory"
	if res.Status == "" {
		res.Status = "COMPLETED"
	}
	return res, nil
}

func TestTriggerSyncEndpoint(t *testing.T) {
	serveSync := func(h *Handler, id string, body string) *httptest.ResponseRecorder {
		r := mux.NewRouter()
		h.RegisterRoutes(r)
		req := httptest.NewRequest(http.MethodPost, "/subscriptions/"+id+"/sync", bytes.NewBufferString(body))
		rec := httptest.NewRecorder()
		r.ServeHTTP(rec, req)
		return rec
	}

	t.Run("returns 404 when subscription missing", func(t *testing.T) {
		h := NewHandlerWithSync(&stubSubManager{sub: nil}, &stubSyncRunner{})
		rec := serveSync(h, "sub-missing", `{}`)
		assert.Equal(t, http.StatusNotFound, rec.Code)
	})

	t.Run("returns 409 when subscription is not ACTIVE", func(t *testing.T) {
		h := NewHandlerWithSync(&stubSubManager{
			sub: &subscription.Subscription{ID: "sub-paused", State: subscription.StatePaused, SourceDomain: "user_features"},
		}, &stubSyncRunner{})
		rec := serveSync(h, "sub-paused", `{}`)
		assert.Equal(t, http.StatusConflict, rec.Code)
	})

	t.Run("returns 503 when syncRunner is nil", func(t *testing.T) {
		h := NewHandler(&stubSubManager{
			sub: &subscription.Subscription{ID: "sub-active", State: subscription.StateActive, SourceDomain: "user_features"},
		})
		rec := serveSync(h, "sub-active", `{}`)
		assert.Equal(t, http.StatusServiceUnavailable, rec.Code)
	})

	t.Run("dispatches user_features sync to worker pool runner", func(t *testing.T) {
		runner := &stubSyncRunner{
			result: subscription.SyncResult{RowsProcessed: 1, Duration: 10 * time.Millisecond},
		}
		h := NewHandlerWithSync(&stubSubManager{
			sub: &subscription.Subscription{ID: "sub-feat", State: subscription.StateActive, SourceDomain: "user_features"},
		}, runner)
		rec := serveSync(h, "sub-feat", `{"job_id":"job-123","user_features":[{"user_id":"u1","clv":99.5}]}`)
		require.Equal(t, http.StatusAccepted, rec.Code, "body=%s", rec.Body.String())
		assert.Equal(t, "user_features", runner.calledType)
		assert.Equal(t, "job-123", runner.lastJobID)
		assert.Equal(t, "sub-feat", runner.lastSubID)
		assert.Equal(t, 1, runner.featureRows)

		var payload map[string]any
		require.NoError(t, json.Unmarshal(rec.Body.Bytes(), &payload))
		assert.Equal(t, "COMPLETED", payload["status"])
		assert.Equal(t, "job-123", payload["job_id"])
	})

	t.Run("returns 503 when redis is not configured for sync", func(t *testing.T) {
		runner := &stubSyncRunner{
			result: subscription.SyncResult{Status: "FAILED"},
			err:    errors.New("redis client not configured"),
		}
		h := NewHandlerWithSync(&stubSubManager{
			sub: &subscription.Subscription{ID: "sub-inv", State: subscription.StateActive, SourceDomain: "inventory"},
		}, runner)
		rec := serveSync(h, "sub-inv", `{"store_ids":["store-1"]}`)
		assert.Equal(t, http.StatusServiceUnavailable, rec.Code)
	})
}
