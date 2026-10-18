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

// mockSubscriptionManager implements SubscriptionManager for API testing.
type mockSubscriptionManager struct {
	subscriptions map[string]*subscription.Subscription
	createErr     error
	getErr        error
	listErr       error
}

func newMockSubscriptionManager() *mockSubscriptionManager {
	return &mockSubscriptionManager{
		subscriptions: make(map[string]*subscription.Subscription),
	}
}

func (m *mockSubscriptionManager) Create(ctx context.Context, req subscription.CreateRequest, createdBy string) (*subscription.Subscription, error) {
	if m.createErr != nil {
		return nil, m.createErr
	}
	sub := &subscription.Subscription{
		ID:            "sub-uuid-1",
		Name:          req.Name,
		Description:   req.Description,
		SourceDomain:  req.SourceDomain,
		SourceEvents:  req.SourceEvents,
		SinkType:      req.SinkType,
		SinkConfig:    req.SinkConfig,
		TransformSpec: req.TransformSpec,
		Schedule:      req.Schedule,
		State:         subscription.StateDraft,
		CreatedAt:     time.Now(),
		UpdatedAt:     time.Now(),
		CreatedBy:     createdBy,
	}
	m.subscriptions[sub.ID] = sub
	return sub, nil
}

func (m *mockSubscriptionManager) Get(ctx context.Context, id string) (*subscription.Subscription, error) {
	if m.getErr != nil {
		return nil, m.getErr
	}
	return m.subscriptions[id], nil
}

func (m *mockSubscriptionManager) List(ctx context.Context, sourceDomain, sinkType string, state subscription.State) ([]*subscription.Subscription, error) {
	if m.listErr != nil {
		return nil, m.listErr
	}
	var res []*subscription.Subscription
	for _, s := range m.subscriptions {
		res = append(res, s)
	}
	return res, nil
}

func (m *mockSubscriptionManager) Activate(ctx context.Context, id string) error {
	sub, ok := m.subscriptions[id]
	if !ok {
		return errors.New("subscription not found")
	}
	sub.State = subscription.StateActive
	return nil
}

func (m *mockSubscriptionManager) Pause(ctx context.Context, id string) error {
	sub, ok := m.subscriptions[id]
	if !ok {
		return errors.New("subscription not found")
	}
	sub.State = subscription.StatePaused
	return nil
}

func (m *mockSubscriptionManager) Archive(ctx context.Context, id string) error {
	sub, ok := m.subscriptions[id]
	if !ok {
		return errors.New("subscription not found")
	}
	sub.State = subscription.StateArchived
	return nil
}

func setupTestRouter(mgr SubscriptionManager) *mux.Router {
	h := NewHandler(mgr)
	r := mux.NewRouter()
	h.RegisterRoutes(r.PathPrefix("/api/v1").Subrouter())
	return r
}

func TestAPIHealth(t *testing.T) {
	mgr := newMockSubscriptionManager()
	r := setupTestRouter(mgr)

	req := httptest.NewRequest("GET", "/api/v1/health", nil)
	rec := httptest.NewRecorder()
	r.ServeHTTP(rec, req)

	assert.Equal(t, http.StatusOK, rec.Code)
	var body map[string]string
	err := json.NewDecoder(rec.Body).Decode(&body)
	require.NoError(t, err)
	assert.Equal(t, "healthy", body["status"])
}

func TestAPICreateSubscription(t *testing.T) {
	mgr := newMockSubscriptionManager()
	r := setupTestRouter(mgr)

	createPayload := subscription.CreateRequest{
		Name:         "User Affinity to Redis",
		SourceDomain: "analytics",
		SinkType:     "redis",
		Schedule:     "0 0 * * *",
	}
	body, _ := json.Marshal(createPayload)

	req := httptest.NewRequest("POST", "/api/v1/subscriptions", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-User-Email", "data-eng@instacommerce.com")
	rec := httptest.NewRecorder()
	r.ServeHTTP(rec, req)

	assert.Equal(t, http.StatusCreated, rec.Code)
	var created subscription.Subscription
	err := json.NewDecoder(rec.Body).Decode(&created)
	require.NoError(t, err)
	assert.Equal(t, "sub-uuid-1", created.ID)
	assert.Equal(t, "User Affinity to Redis", created.Name)
	assert.Equal(t, subscription.StateDraft, created.State)
	assert.Equal(t, "data-eng@instacommerce.com", created.CreatedBy)
}

func TestAPIGetSubscription(t *testing.T) {
	mgr := newMockSubscriptionManager()
	mgr.subscriptions["sub-123"] = &subscription.Subscription{
		ID:    "sub-123",
		Name:  "Dark Store Inventory Sync",
		State: subscription.StateActive,
	}
	r := setupTestRouter(mgr)

	req := httptest.NewRequest("GET", "/api/v1/subscriptions/sub-123", nil)
	rec := httptest.NewRecorder()
	r.ServeHTTP(rec, req)

	assert.Equal(t, http.StatusOK, rec.Code)
	var got subscription.Subscription
	err := json.NewDecoder(rec.Body).Decode(&got)
	require.NoError(t, err)
	assert.Equal(t, "sub-123", got.ID)
	assert.Equal(t, "Dark Store Inventory Sync", got.Name)
}

func TestAPIActivateAndPauseSubscription(t *testing.T) {
	mgr := newMockSubscriptionManager()
	mgr.subscriptions["sub-456"] = &subscription.Subscription{
		ID:    "sub-456",
		Name:  "Fraud Risk Score Sync",
		State: subscription.StateDraft,
	}
	r := setupTestRouter(mgr)

	// Activate
	req := httptest.NewRequest("POST", "/api/v1/subscriptions/sub-456/activate", nil)
	rec := httptest.NewRecorder()
	r.ServeHTTP(rec, req)
	assert.Equal(t, http.StatusOK, rec.Code)
	assert.Equal(t, subscription.StateActive, mgr.subscriptions["sub-456"].State)

	// Pause
	reqPause := httptest.NewRequest("POST", "/api/v1/subscriptions/sub-456/pause", nil)
	recPause := httptest.NewRecorder()
	r.ServeHTTP(recPause, reqPause)
	assert.Equal(t, http.StatusOK, recPause.Code)
	assert.Equal(t, subscription.StatePaused, mgr.subscriptions["sub-456"].State)
}
