package subscription

import (
	"context"
	"database/sql"
	"database/sql/driver"
	"io"
	"strings"
	"sync"
	"testing"

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
