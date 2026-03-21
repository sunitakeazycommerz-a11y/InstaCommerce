package reconciliation

import "errors"

var (
	ErrReconciliationAlreadyRunning = errors.New("reconciliation is already running")
	ErrRunNotFound                  = errors.New("reconciliation run not found")
	ErrMismatchNotFound             = errors.New("mismatch not found")
)
