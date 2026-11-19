package subscription

import (
	"context"
	"errors"
	"io"
	"net/http"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

type timeoutNetErr struct{}

func (timeoutNetErr) Error() string   { return "i/o timeout" }
func (timeoutNetErr) Timeout() bool   { return true }
func (timeoutNetErr) Temporary() bool { return true }

func TestClassifyHTTPStatus_Table(t *testing.T) {
	cases := []struct {
		code int
		want FailureKind
	}{
		{http.StatusBadRequest, FailurePermanent},
		{http.StatusUnauthorized, FailurePermanent},
		{http.StatusForbidden, FailurePermanent},
		{http.StatusNotFound, FailurePermanent},
		{http.StatusConflict, FailurePermanent},
		{http.StatusUnprocessableEntity, FailurePermanent},
		{http.StatusRequestTimeout, FailureTransient},
		{http.StatusTooEarly, FailureTransient},
		{http.StatusTooManyRequests, FailureTransient},
		{http.StatusInternalServerError, FailureTransient},
		{http.StatusBadGateway, FailureTransient},
		{http.StatusServiceUnavailable, FailureTransient},
		{http.StatusGatewayTimeout, FailureTransient},
	}

	for _, tc := range cases {
		t.Run(http.StatusText(tc.code), func(t *testing.T) {
			assert.Equal(t, tc.want, ClassifyHTTPStatus(tc.code))
		})
	}
}

func TestClassifyError_Table(t *testing.T) {
	cases := []struct {
		name string
		err  error
		want FailureKind
	}{
		{name: "permanent 422 sink error", err: NewSinkError(422, "schema rejected", nil), want: FailurePermanent},
		{name: "transient 503 sink error", err: NewSinkError(503, "upstream busy", nil), want: FailureTransient},
		{name: "transient 429 sink error", err: NewSinkError(429, "rate limited", nil), want: FailureTransient},
		{name: "network timeout", err: timeoutNetErr{}, want: FailureTransient},
		{name: "unexpected EOF", err: io.ErrUnexpectedEOF, want: FailureTransient},
		{name: "context deadline", err: context.DeadlineExceeded, want: FailureTransient},
		{name: "context canceled", err: context.Canceled, want: FailurePermanent},
		{name: "permanent sentinel", err: ErrPermanentFailure, want: FailurePermanent},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			assert.Equal(t, tc.want, ClassifyError(tc.err))
		})
	}
}

func TestExecuteWithRetry_TransientRetriesAndPermanentStopsImmediately(t *testing.T) {
	policy := RetryPolicy{MaxAttempts: 3, BaseDelay: time.Millisecond, MaxDelay: 2 * time.Millisecond}

	t.Run("permanent 4xx stops after 1 attempt", func(t *testing.T) {
		var calls int
		attempts, err := ExecuteWithRetry(context.Background(), "webhook_sink", nil, policy, func(context.Context) error {
			calls++
			return NewSinkError(400, "bad mapping", nil)
		})
		require.Error(t, err)
		assert.Equal(t, 1, calls)
		assert.Equal(t, 1, attempts)
		assert.ErrorIs(t, err, ErrPermanentFailure)
	})

	t.Run("transient 503 succeeds on third attempt", func(t *testing.T) {
		var calls int
		attempts, err := ExecuteWithRetry(context.Background(), "webhook_sink", nil, policy, func(context.Context) error {
			calls++
			if calls < 3 {
				return NewSinkError(503, "unavailable", nil)
			}
			return nil
		})
		require.NoError(t, err)
		assert.Equal(t, 3, calls)
		assert.Equal(t, 3, attempts)
	})

	t.Run("context cancel stops retry wait promptly", func(t *testing.T) {
		ctx, cancel := context.WithCancel(context.Background())
		var calls int
		attempts, err := ExecuteWithRetry(ctx, "webhook_sink", nil, RetryPolicy{
			MaxAttempts: 5,
			BaseDelay:   time.Second,
			MaxDelay:    time.Second,
		}, func(context.Context) error {
			calls++
			cancel()
			return errors.New("not used")
		})
		require.Error(t, err)
		assert.Equal(t, 1, calls)
		assert.Equal(t, 1, attempts)
	})
}
