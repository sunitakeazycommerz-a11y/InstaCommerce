package httpclient

import (
	"sync"
	"time"
)

// CircuitState represents the state of the circuit breaker.
type CircuitState int

const (
	// Closed means the circuit is healthy and requests flow through normally.
	Closed CircuitState = iota
	// Open means the circuit has tripped; requests are rejected immediately.
	Open
	// HalfOpen means the circuit is testing whether the upstream has recovered.
	HalfOpen
)

// String returns a human-readable circuit state name.
func (s CircuitState) String() string {
	switch s {
	case Closed:
		return "closed"
	case Open:
		return "open"
	case HalfOpen:
		return "half-open"
	default:
		return "unknown"
	}
}

// CircuitBreaker implements a thread-safe three-state circuit breaker.
type CircuitBreaker struct {
	failures         int
	threshold        int
	resetTimeout     time.Duration
	state            CircuitState
	lastFailure      time.Time
	halfOpenInFlight bool
	mu               sync.Mutex
}

func newCircuitBreaker(threshold int, resetTimeout time.Duration) *CircuitBreaker {
	if threshold < 1 {
		threshold = 1
	}
	if resetTimeout <= 0 {
		resetTimeout = 30 * time.Second
	}
	return &CircuitBreaker{
		threshold:    threshold,
		resetTimeout: resetTimeout,
		state:        Closed,
	}
}

func (cb *CircuitBreaker) allow() bool {
	cb.mu.Lock()
	defer cb.mu.Unlock()

	switch cb.state {
	case Closed:
		return true
	case Open:
		if time.Since(cb.lastFailure) > cb.resetTimeout {
			cb.state = HalfOpen
			cb.halfOpenInFlight = true
			return true
		}
		return false
	case HalfOpen:
		if !cb.halfOpenInFlight {
			cb.halfOpenInFlight = true
			return true
		}
		return false
	default:
		return true
	}
}

func (cb *CircuitBreaker) recordSuccess() {
	cb.mu.Lock()
	defer cb.mu.Unlock()
	cb.failures = 0
	cb.halfOpenInFlight = false
	cb.state = Closed
}

func (cb *CircuitBreaker) recordFailure() {
	cb.mu.Lock()
	defer cb.mu.Unlock()
	cb.failures++
	cb.lastFailure = time.Now()
	cb.halfOpenInFlight = false
	if cb.state == HalfOpen || cb.failures >= cb.threshold {
		cb.state = Open
	}
}

func (cb *CircuitBreaker) abortProbe() {
	cb.mu.Lock()
	defer cb.mu.Unlock()
	if cb.state == HalfOpen {
		cb.halfOpenInFlight = false
	}
}
