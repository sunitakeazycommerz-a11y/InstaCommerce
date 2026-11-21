package subscription

import (
	"context"
	"database/sql"
	"database/sql/driver"
	"errors"
	"io"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestValidateTransition_StateMachineTable(t *testing.T) {
	cases := []struct {
		from    State
		to      State
		allowed bool
	}{
		{StateDraft, StateValidated, true},
		{StateDraft, StateActive, true},
		{StateDraft, StateArchived, true},
		{StateDraft, StatePaused, false},
		{StateValidated, StateActive, true},
		{StateValidated, StateArchived, true},
		{StateValidated, StatePaused, false},
		{StateActive, StatePaused, true},
		{StateActive, StateArchived, true},
		{StateActive, StateActive, true},
		{StatePaused, StateActive, true},
		{StatePaused, StateArchived, true},
		{StatePaused, StatePaused, true},
		{StateArchived, StateActive, false},
		{StateArchived, StatePaused, false},
		{StateArchived, StateArchived, true},
		{State("UNKNOWN"), StateActive, false},
	}

	for _, tc := range cases {
		name := string(tc.from) + "_to_" + string(tc.to)
		t.Run(name, func(t *testing.T) {
			err := ValidateTransition(tc.from, tc.to)
			if tc.allowed {
				require.NoError(t, err)
			} else {
				require.ErrorIs(t, err, ErrInvalidStateTransition)
			}
		})
	}
}

type subStateStubConnector struct {
	mu     sync.Mutex
	states map[string]State
}

func (s *subStateStubConnector) Connect(context.Context) (driver.Conn, error) { return s, nil }
func (s *subStateStubConnector) Driver() driver.Driver                        { return s }
func (s *subStateStubConnector) Open(string) (driver.Conn, error)             { return s, nil }
func (s *subStateStubConnector) Prepare(string) (driver.Stmt, error)          { return nil, driver.ErrSkip }
func (s *subStateStubConnector) Close() error                                 { return nil }
func (s *subStateStubConnector) Begin() (driver.Tx, error)                    { return nil, driver.ErrSkip }

func (s *subStateStubConnector) QueryContext(_ context.Context, query string, args []driver.NamedValue) (driver.Rows, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if strings.Contains(query, "SELECT state FROM subscriptions") && len(args) == 1 {
		id, _ := args[0].Value.(string)
		st, ok := s.states[id]
		if !ok {
			return &singleColRows{}, nil
		}
		return &singleColRows{vals: []driver.Value{string(st)}}, nil
	}
	return &singleColRows{}, nil
}

func (s *subStateStubConnector) ExecContext(_ context.Context, query string, args []driver.NamedValue) (driver.Result, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if strings.Contains(query, "UPDATE subscriptions") && len(args) == 4 {
		newState, _ := args[0].Value.(string)
		id, _ := args[2].Value.(string)
		expected, _ := args[3].Value.(string)
		if cur, ok := s.states[id]; ok && string(cur) == expected {
			s.states[id] = State(newState)
			return driver.RowsAffected(1), nil
		}
		return driver.RowsAffected(0), nil
	}
	return driver.RowsAffected(0), nil
}

type singleColRows struct {
	vals []driver.Value
	idx  int
}

func (r *singleColRows) Columns() []string { return []string{"state"} }
func (r *singleColRows) Close() error      { return nil }
func (r *singleColRows) Next(dest []driver.Value) error {
	if r.idx >= len(r.vals) {
		return io.EOF
	}
	dest[0] = r.vals[r.idx]
	r.idx++
	return nil
}

func TestServiceTransition_EnforcesStateMachineAndIdempotency(t *testing.T) {
	stub := &subStateStubConnector{
		states: map[string]State{
			"sub-1": StateDraft,
		},
	}
	db := sql.OpenDB(stub)
	defer db.Close()

	svc := NewServiceWithDB(db, nil)
	ctx := context.Background()

	require.ErrorIs(t, svc.Pause(ctx, "missing"), ErrSubscriptionNotFound)
	require.ErrorIs(t, svc.Pause(ctx, "sub-1"), ErrInvalidStateTransition)

	require.NoError(t, svc.Validate(ctx, "sub-1"))
	assert.Equal(t, StateValidated, stub.states["sub-1"])

	require.NoError(t, svc.Activate(ctx, "sub-1"))
	require.NoError(t, svc.Activate(ctx, "sub-1"))
	assert.Equal(t, StateActive, stub.states["sub-1"])

	require.NoError(t, svc.Pause(ctx, "sub-1"))
	require.NoError(t, svc.Activate(ctx, "sub-1"))
	require.NoError(t, svc.Archive(ctx, "sub-1"))
	require.ErrorIs(t, svc.Activate(ctx, "sub-1"), ErrInvalidStateTransition)
}

type crudStubConnector struct {
	mu      sync.Mutex
	subs    map[string]*Subscription
	pingErr error
	execErr error
	listErr error
}

func (c *crudStubConnector) Connect(context.Context) (driver.Conn, error) { return c, nil }
func (c *crudStubConnector) Driver() driver.Driver                        { return c }
func (c *crudStubConnector) Open(string) (driver.Conn, error)             { return c, nil }
func (c *crudStubConnector) Prepare(string) (driver.Stmt, error)          { return nil, driver.ErrSkip }
func (c *crudStubConnector) Close() error                                 { return nil }
func (c *crudStubConnector) Begin() (driver.Tx, error)                    { return nil, driver.ErrSkip }
func (c *crudStubConnector) Ping(context.Context) error                   { return c.pingErr }

func (c *crudStubConnector) ExecContext(_ context.Context, query string, args []driver.NamedValue) (driver.Result, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.execErr != nil {
		return nil, c.execErr
	}
	if strings.Contains(query, "INSERT INTO subscriptions") && len(args) == 12 {
		c.subs[args[0].Value.(string)] = &Subscription{
			ID:            args[0].Value.(string),
			Name:          args[1].Value.(string),
			Description:   args[2].Value.(string),
			SourceDomain:  args[3].Value.(string),
			SinkType:      args[4].Value.(string),
			SinkConfig:    args[5].Value.(string),
			TransformSpec: args[6].Value.(string),
			Schedule:      args[7].Value.(string),
			State:         State(args[8].Value.(string)),
			CreatedAt:     args[9].Value.(time.Time),
			UpdatedAt:     args[10].Value.(time.Time),
			CreatedBy:     args[11].Value.(string),
		}
		return driver.RowsAffected(1), nil
	}
	return driver.RowsAffected(0), nil
}

func (c *crudStubConnector) QueryContext(_ context.Context, query string, args []driver.NamedValue) (driver.Rows, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if strings.Contains(query, "WHERE subscription_id = $1") && len(args) == 1 {
		id := args[0].Value.(string)
		sub, ok := c.subs[id]
		if !ok {
			return &subRows{}, nil
		}
		return &subRows{items: []*Subscription{sub}}, nil
	}
	if strings.Contains(query, "FROM subscriptions") {
		if c.listErr != nil {
			return nil, c.listErr
		}
		var out []*Subscription
		src, _ := args[0].Value.(string)
		sink, _ := args[1].Value.(string)
		st, _ := args[2].Value.(string)
		for _, sub := range c.subs {
			if (src == "" || sub.SourceDomain == src) &&
				(sink == "" || sub.SinkType == sink) &&
				(st == "" || string(sub.State) == st) {
				out = append(out, sub)
			}
		}
		return &subRows{items: out}, nil
	}
	return &subRows{}, nil
}

type subRows struct {
	items []*Subscription
	idx   int
}

func (r *subRows) Columns() []string {
	return []string{
		"subscription_id", "name", "description", "source_domain",
		"sink_type", "sink_config", "transform_spec", "schedule",
		"state", "created_at", "updated_at", "created_by",
	}
}
func (r *subRows) Close() error { return nil }
func (r *subRows) Next(dest []driver.Value) error {
	if r.idx >= len(r.items) {
		return io.EOF
	}
	s := r.items[r.idx]
	r.idx++
	dest[0] = s.ID
	dest[1] = s.Name
	dest[2] = s.Description
	dest[3] = s.SourceDomain
	dest[4] = s.SinkType
	dest[5] = s.SinkConfig
	dest[6] = s.TransformSpec
	dest[7] = s.Schedule
	dest[8] = string(s.State)
	dest[9] = s.CreatedAt
	dest[10] = s.UpdatedAt
	dest[11] = s.CreatedBy
	return nil
}

func TestServiceCRUDAndNilGuards(t *testing.T) {
	var nilSvc *Service
	assert.NoError(t, nilSvc.Close())
	assert.Error(t, nilSvc.Ping(context.Background()))
	assert.Nil(t, nilSvc.DB())
	_, err := nilSvc.Get(context.Background(), "id")
	assert.Error(t, err)
	_, err = nilSvc.List(context.Background(), "", "", "")
	assert.Error(t, err)

	stub := &crudStubConnector{subs: make(map[string]*Subscription)}
	db := sql.OpenDB(stub)
	svc := NewServiceWithDB(db, nil)
	defer svc.Close()

	require.NotNil(t, svc.DB())
	require.NoError(t, svc.Ping(context.Background()))

	_, err = svc.Create(context.Background(), CreateRequest{Name: "  "}, "alice")
	require.ErrorIs(t, err, ErrInvalidSubscription)

	created, err := svc.Create(context.Background(), CreateRequest{
		Name:         "  User CLV Sync ",
		Description:  "sync clv",
		SourceDomain: "analytics",
		SinkType:     "redis",
		Schedule:     "0 * * * *",
	}, "alice@instacommerce.com")
	require.NoError(t, err)
	assert.Equal(t, "User CLV Sync", created.Name)
	assert.Equal(t, StateDraft, created.State)

	got, err := svc.Get(context.Background(), created.ID)
	require.NoError(t, err)
	require.NotNil(t, got)
	assert.Equal(t, created.ID, got.ID)

	missing, err := svc.Get(context.Background(), "does-not-exist")
	require.NoError(t, err)
	assert.Nil(t, missing)

	list, err := svc.List(context.Background(), "analytics", "redis", StateDraft)
	require.NoError(t, err)
	require.Len(t, list, 1)

	emptyList, err := svc.List(context.Background(), "orders", "redis", StateDraft)
	require.NoError(t, err)
	assert.Empty(t, emptyList)

	stub.pingErr = errors.New("ping fail")
	assert.Error(t, svc.Ping(context.Background()))

	stub.execErr = errors.New("insert fail")
	_, err = svc.Create(context.Background(), CreateRequest{
		Name: "Fail", SourceDomain: "orders", SinkType: "redis",
	}, "bob")
	require.Error(t, err)
}
