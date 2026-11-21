package subscription

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"syscall"
	"time"
)

// FailureKind distinguishes retryable sink failures from permanent 4xx rejections.
type FailureKind string

const (
	FailureTransient FailureKind = "transient"
	FailurePermanent FailureKind = "permanent"
)

var (
	// ErrPermanentFailure marks an unretryable error (e.g., 4xx client rejection).
	ErrPermanentFailure = errors.New("permanent sink failure")
	// ErrTransientFailure marks a retryable transient error (e.g., 429/5xx/timeout).
	ErrTransientFailure = errors.New("transient sink failure")
)

// HTTPStatusError exposes an HTTP status code from a sink error.
type HTTPStatusError interface {
	HTTPStatus() int
}

// SinkError captures a downstream sink response code and retryability classification.
type SinkError struct {
	StatusCode int
	Kind       FailureKind
	RetryAfter time.Duration
	Message    string
	Err        error
}

// NewSinkError constructs a SinkError classified from an HTTP status code.
func NewSinkError(statusCode int, message string, err error) *SinkError {
	return &SinkError{
		StatusCode: statusCode,
		Kind:       ClassifyHTTPStatus(statusCode),
		Message:    message,
		Err:        err,
	}
}

func (e *SinkError) Error() string {
	if e.Message != "" && e.Err != nil {
		return fmt.Sprintf("sink status %d (%s): %s: %v", e.StatusCode, e.Kind, e.Message, e.Err)
	}
	if e.Message != "" {
		return fmt.Sprintf("sink status %d (%s): %s", e.StatusCode, e.Kind, e.Message)
	}
	if e.Err != nil {
		return fmt.Sprintf("sink status %d (%s): %v", e.StatusCode, e.Kind, e.Err)
	}
	return fmt.Sprintf("sink status %d (%s)", e.StatusCode, e.Kind)
}

func (e *SinkError) Unwrap() error {
	if e.Err != nil {
		return e.Err
	}
	if e.Kind == FailurePermanent {
		return ErrPermanentFailure
	}
	return ErrTransientFailure
}

// HTTPStatus returns the HTTP status code associated with the sink failure.
func (e *SinkError) HTTPStatus() int {
	return e.StatusCode
}

// IsPermanent returns true if the failure must not be retried.
func (e *SinkError) IsPermanent() bool {
	return e.Kind == FailurePermanent
}

// IsTransient returns true if the failure is eligible for backoff retry.
func (e *SinkError) IsTransient() bool {
	return e.Kind == FailureTransient
}

// ClassifyHTTPStatus maps an HTTP response code to transient or permanent failure kind.
func ClassifyHTTPStatus(statusCode int) FailureKind {
	if statusCode == http.StatusRequestTimeout ||
		statusCode == http.StatusTooEarly ||
		statusCode == http.StatusTooManyRequests {
		return FailureTransient
	}
	if statusCode >= 500 && statusCode <= 599 {
		return FailureTransient
	}
	if statusCode >= 400 && statusCode <= 499 {
		return FailurePermanent
	}
	return FailureTransient
}

// ClassifyError determines whether err is transient (retryable) or permanent.
func ClassifyError(err error) FailureKind {
	if err == nil {
		return FailurePermanent
	}
	var sinkErr *SinkError
	if errors.As(err, &sinkErr) {
		if sinkErr.Kind == FailurePermanent || sinkErr.Kind == FailureTransient {
			return sinkErr.Kind
		}
		if sinkErr.StatusCode > 0 {
			return ClassifyHTTPStatus(sinkErr.StatusCode)
		}
	}
	var statusErr HTTPStatusError
	if errors.As(err, &statusErr) && statusErr.HTTPStatus() > 0 {
		return ClassifyHTTPStatus(statusErr.HTTPStatus())
	}
	if errors.Is(err, ErrPermanentFailure) ||
		errors.Is(err, context.Canceled) ||
		errors.Is(err, ErrInvalidSubscription) ||
		errors.Is(err, ErrInvalidStateTransition) {
		return FailurePermanent
	}
	var syntaxErr *json.SyntaxError
	var typeErr *json.UnmarshalTypeError
	if errors.As(err, &syntaxErr) || errors.As(err, &typeErr) {
		return FailurePermanent
	}
	if errors.Is(err, ErrTransientFailure) ||
		errors.Is(err, context.DeadlineExceeded) ||
		errors.Is(err, io.EOF) ||
		errors.Is(err, io.ErrUnexpectedEOF) {
		return FailureTransient
	}
	var netErr net.Error
	if errors.As(err, &netErr) && netErr.Timeout() {
		return FailureTransient
	}
	if errors.Is(err, syscall.ECONNRESET) ||
		errors.Is(err, syscall.ECONNREFUSED) ||
		errors.Is(err, syscall.EPIPE) {
		return FailureTransient
	}
	return FailurePermanent
}

// RetryPolicy configures bounded exponential backoff for transient sink failures.
type RetryPolicy struct {
	MaxAttempts int
	BaseDelay   time.Duration
	MaxDelay    time.Duration
}

// DefaultRetryPolicy returns default retry settings for transient reverse ETL sink errors.
func DefaultRetryPolicy() RetryPolicy {
	return RetryPolicy{
		MaxAttempts: 3,
		BaseDelay:   50 * time.Millisecond,
		MaxDelay:    2 * time.Second,
	}
}

func (p RetryPolicy) normalized() RetryPolicy {
	defaults := DefaultRetryPolicy()
	if p == (RetryPolicy{}) {
		return defaults
	}
	if p.MaxAttempts <= 0 {
		p.MaxAttempts = defaults.MaxAttempts
	}
	if p.BaseDelay < 0 {
		p.BaseDelay = 0
	}
	if p.MaxDelay <= 0 {
		p.MaxDelay = defaults.MaxDelay
	}
	return p
}

// ExecuteWithRetry runs fn up to MaxAttempts times, retrying only FailureTransient errors.
func ExecuteWithRetry(
	ctx context.Context,
	syncType string,
	metrics *WorkerMetrics,
	policy RetryPolicy,
	fn func(context.Context) error,
) (int, error) {
	p := policy.normalized()
	delay := p.BaseDelay

	var lastErr error
	for attempt := 1; attempt <= p.MaxAttempts; attempt++ {
		if ctxErr := ctx.Err(); ctxErr != nil {
			if lastErr != nil {
				return attempt - 1, lastErr
			}
			return attempt - 1, ctxErr
		}

		err := fn(ctx)
		if err == nil {
			return attempt, nil
		}
		lastErr = err

		kind := ClassifyError(err)
		if metrics != nil && metrics.SyncRetriesTotal != nil {
			metrics.SyncRetriesTotal.WithLabelValues(syncType, string(kind)).Inc()
		}
		if kind == FailurePermanent || attempt == p.MaxAttempts {
			return attempt, err
		}

		wait := delay
		var sinkErr *SinkError
		if errors.As(err, &sinkErr) && sinkErr.RetryAfter > wait {
			wait = sinkErr.RetryAfter
		}
		if wait > p.MaxDelay {
			wait = p.MaxDelay
		}
		if wait > 0 {
			timer := time.NewTimer(wait)
			select {
			case <-ctx.Done():
				timer.Stop()
				return attempt, ctx.Err()
			case <-timer.C:
			}
		}

		if delay == 0 {
			continue
		}
		nextDelay := delay * 2
		if nextDelay > p.MaxDelay {
			nextDelay = p.MaxDelay
		}
		delay = nextDelay
	}

	return p.MaxAttempts, lastErr
}
