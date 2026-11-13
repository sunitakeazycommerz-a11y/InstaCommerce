package health

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestCheckTCPBrokers_FallbackAndValidation(t *testing.T) {
	healthyLn, err := net.Listen("tcp", "127.0.0.1:0")
	require.NoError(t, err)
	defer healthyLn.Close()

	closedLn, err := net.Listen("tcp", "127.0.0.1:0")
	require.NoError(t, err)
	closedAddr := closedLn.Addr().String()
	_ = closedLn.Close()

	err = CheckTCPBrokers(context.Background(), []string{" ", closedAddr, healthyLn.Addr().String()}, time.Second)
	assert.NoError(t, err)

	err = CheckTCPBrokers(context.Background(), []string{"", "   "}, time.Second)
	assert.ErrorContains(t, err, "no brokers configured")
}

func TestCheckTCPBrokersWithDial_SplitsDeadlineAcrossBrokers(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 200*time.Millisecond)
	defer cancel()

	var calls int
	err := CheckTCPBrokersWithDial(ctx, []string{"hang-1:9092", "healthy-2:9092"}, 2*time.Second, func(dialCtx context.Context, broker string) error {
		calls++
		if broker == "hang-1:9092" {
			<-dialCtx.Done()
			return dialCtx.Err()
		}
		return nil
	})
	assert.NoError(t, err)
	assert.Equal(t, 2, calls)
}

func TestCheckTCPBrokersWithDial_RespectsCanceledContext(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	err := CheckTCPBrokersWithDial(ctx, []string{"127.0.0.1:9092"}, time.Second, func(context.Context, string) error {
		return errors.New("should not be called")
	})
	assert.ErrorIs(t, err, context.Canceled)
}

func TestShutdownServer_MarksNotReadyAndClosesInFlight(t *testing.T) {
	h := NewHandler()
	h.SetReady(true)

	inFlight := make(chan struct{})
	release := make(chan struct{})
	srv := &http.Server{
		Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			close(inFlight)
			<-release
			w.WriteHeader(http.StatusOK)
		}),
	}

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	require.NoError(t, err)
	go func() { _ = srv.Serve(ln) }()

	reqDone := make(chan int, 1)
	go func() {
		resp, reqErr := http.Get("http://" + ln.Addr().String())
		if reqErr == nil {
			_ = resp.Body.Close()
			reqDone <- resp.StatusCode
		}
	}()

	<-inFlight
	done := make(chan struct{})
	go func() {
		ShutdownServer(srv, h, 2*time.Second, slog.New(slog.NewTextHandler(io.Discard, nil)))
		close(done)
	}()

	require.Eventually(t, func() bool {
		req := httptest.NewRequest(http.MethodGet, "/ready", nil)
		rec := httptest.NewRecorder()
		h.ReadyHandler()(rec, req)
		return rec.Code == http.StatusServiceUnavailable
	}, time.Second, time.Millisecond)

	close(release)
	<-done
	assert.Equal(t, http.StatusOK, <-reqDone)
}
