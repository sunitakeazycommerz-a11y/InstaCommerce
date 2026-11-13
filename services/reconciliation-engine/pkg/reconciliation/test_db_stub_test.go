package reconciliation

import (
	"database/sql"
	"database/sql/driver"

	"github.com/instacommerce/reconciliation-engine/internal/testdb"
)

type recordedStatement = testdb.RecordedStatement
type stubRows struct {
	columns []string
	data    [][]driver.Value
	inner   *testdb.StubRows
}

func (r *stubRows) Columns() []string { return r.columns }
func (r *stubRows) Close() error      { return nil }
func (r *stubRows) Next(dest []driver.Value) error {
	if r.inner == nil {
		r.inner = &testdb.StubRows{ColumnsList: r.columns, Data: r.data}
	}
	return r.inner.Next(dest)
}

type stubDBState struct {
	*testdb.State
}

func newStubDB(
	queryFn func(connID int64, query string, args []driver.NamedValue) (driver.Rows, error),
	execFn func(connID int64, query string, args []driver.NamedValue) (driver.Result, error),
) (*sql.DB, *stubDBState) {
	db, state := testdb.New(queryFn, execFn)
	return db, &stubDBState{State: state}
}

func (s *stubDBState) recorded() []recordedStatement {
	return s.State.Recorded()
}
