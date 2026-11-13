package testdb

import (
	"context"
	"database/sql"
	"database/sql/driver"
	"io"
	"sync"
	"sync/atomic"
)

type RecordedStatement struct {
	ConnID int64
	Query  string
	Args   []driver.NamedValue
	IsExec bool
}

type StubRows struct {
	ColumnsList []string
	Data        [][]driver.Value
	idx         int
}

func (r *StubRows) Columns() []string { return r.ColumnsList }
func (r *StubRows) Close() error      { return nil }
func (r *StubRows) Next(dest []driver.Value) error {
	if r.idx >= len(r.Data) {
		return io.EOF
	}
	copy(dest, r.Data[r.idx])
	r.idx++
	return nil
}

type stubTx struct {
	state *State
}

func (t *stubTx) Commit() error {
	t.state.mu.Lock()
	t.state.Commits++
	t.state.mu.Unlock()
	return nil
}

func (t *stubTx) Rollback() error {
	t.state.mu.Lock()
	t.state.Rollbacks++
	t.state.mu.Unlock()
	return nil
}

type State struct {
	mu         sync.Mutex
	nextConnID atomic.Int64
	Statements []RecordedStatement
	Commits    int
	Rollbacks  int
	queryFunc  func(connID int64, query string, args []driver.NamedValue) (driver.Rows, error)
	execFunc   func(connID int64, query string, args []driver.NamedValue) (driver.Result, error)
}

func New(
	queryFn func(connID int64, query string, args []driver.NamedValue) (driver.Rows, error),
	execFn func(connID int64, query string, args []driver.NamedValue) (driver.Result, error),
) (*sql.DB, *State) {
	state := &State{
		queryFunc: queryFn,
		execFunc:  execFn,
	}
	return sql.OpenDB(&stubConnector{state: state}), state
}

func (s *State) Recorded() []RecordedStatement {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]RecordedStatement, len(s.Statements))
	copy(out, s.Statements)
	return out
}

type stubConnector struct {
	state *State
}

func (c *stubConnector) Connect(context.Context) (driver.Conn, error) {
	id := c.state.nextConnID.Add(1)
	return &stubConn{id: id, state: c.state}, nil
}

func (c *stubConnector) Driver() driver.Driver {
	return &stubDriver{state: c.state}
}

type stubDriver struct {
	state *State
}

func (d *stubDriver) Open(string) (driver.Conn, error) {
	id := d.state.nextConnID.Add(1)
	return &stubConn{id: id, state: d.state}, nil
}

type stubConn struct {
	id    int64
	state *State
}

func (c *stubConn) Prepare(string) (driver.Stmt, error) { return nil, driver.ErrSkip }
func (c *stubConn) Close() error                        { return nil }
func (c *stubConn) Begin() (driver.Tx, error)           { return &stubTx{state: c.state}, nil }
func (c *stubConn) BeginTx(context.Context, driver.TxOptions) (driver.Tx, error) {
	return &stubTx{state: c.state}, nil
}
func (c *stubConn) Ping(context.Context) error { return nil }

func (c *stubConn) ExecContext(_ context.Context, query string, args []driver.NamedValue) (driver.Result, error) {
	copied := make([]driver.NamedValue, len(args))
	copy(copied, args)
	c.state.mu.Lock()
	c.state.Statements = append(c.state.Statements, RecordedStatement{
		ConnID: c.id,
		Query:  query,
		Args:   copied,
		IsExec: true,
	})
	c.state.mu.Unlock()
	if c.state.execFunc != nil {
		return c.state.execFunc(c.id, query, args)
	}
	return driver.RowsAffected(1), nil
}

func (c *stubConn) QueryContext(_ context.Context, query string, args []driver.NamedValue) (driver.Rows, error) {
	copied := make([]driver.NamedValue, len(args))
	copy(copied, args)
	c.state.mu.Lock()
	c.state.Statements = append(c.state.Statements, RecordedStatement{
		ConnID: c.id,
		Query:  query,
		Args:   copied,
	})
	c.state.mu.Unlock()
	if c.state.queryFunc != nil {
		return c.state.queryFunc(c.id, query, args)
	}
	return &StubRows{}, nil
}
