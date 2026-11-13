package main

import (
	"context"
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"

	"github.com/instacommerce/go-shared/pkg/health"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestCheckKafka_MultiBrokerFallback(t *testing.T) {
	closedLn, err := net.Listen("tcp", "127.0.0.1:0")
	require.NoError(t, err)
	closedAddr := closedLn.Addr().String()
	require.NoError(t, closedLn.Close())

	healthyLn, err := net.Listen("tcp", "127.0.0.1:0")
	require.NoError(t, err)
	defer healthyLn.Close()

	var accepted atomic.Int64
	go func() {
		for {
			conn, acceptErr := healthyLn.Accept()
			if acceptErr != nil {
				return
			}
			accepted.Add(1)
			_ = conn.Close()
		}
	}()

	err = checkKafka(context.Background(), []string{closedAddr, healthyLn.Addr().String()})
	assert.NoError(t, err)
	require.Eventually(t, func() bool {
		return accepted.Load() == 1
	}, time.Second, 5*time.Millisecond)
}

func TestCheckKafka_AllBrokersDownReturnsError(t *testing.T) {
	closed1, err := net.Listen("tcp", "127.0.0.1:0")
	require.NoError(t, err)
	addr1 := closed1.Addr().String()
	require.NoError(t, closed1.Close())

	closed2, err := net.Listen("tcp", "127.0.0.1:0")
	require.NoError(t, err)
	addr2 := closed2.Addr().String()
	require.NoError(t, closed2.Close())

	err = checkKafka(context.Background(), []string{addr1, addr2})
	require.Error(t, err)
	assert.Contains(t, err.Error(), addr1)
	assert.Contains(t, err.Error(), addr2)
}

func TestCheckKafka_EmptyAndBlankBrokers(t *testing.T) {
	tests := []struct {
		name    string
		brokers []string
	}{
		{name: "nil slice", brokers: nil},
		{name: "empty slice", brokers: []string{}},
		{name: "blank entries only", brokers: []string{"", "   "}},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			err := checkKafka(context.Background(), tc.brokers)
			require.Error(t, err)
			assert.Contains(t, err.Error(), "no brokers configured")
		})
	}
}

func TestCheckKafka_CanceledContextSkipsDial(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	require.NoError(t, err)
	defer ln.Close()

	var accepted atomic.Int64
	go func() {
		for {
			conn, acceptErr := ln.Accept()
			if acceptErr != nil {
				return
			}
			accepted.Add(1)
			_ = conn.Close()
		}
	}()

	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	err = checkKafka(ctx, []string{ln.Addr().String()})
	require.ErrorIs(t, err, context.Canceled)
	assert.Equal(t, int64(0), accepted.Load())
}

func TestCheckKafka_ExpiredDeadlineSkipsDial(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	require.NoError(t, err)
	defer ln.Close()

	var accepted atomic.Int64
	go func() {
		for {
			conn, acceptErr := ln.Accept()
			if acceptErr != nil {
				return
			}
			accepted.Add(1)
			_ = conn.Close()
		}
	}()

	ctx, cancel := context.WithDeadline(context.Background(), time.Now().Add(-time.Millisecond))
	defer cancel()

	err = checkKafka(ctx, []string{ln.Addr().String()})
	require.ErrorIs(t, err, context.DeadlineExceeded)
	assert.Equal(t, int64(0), accepted.Load())
}

func TestCheckKafkaWithDial_HonorsParentDeadlineAndAllowsFallback(t *testing.T) {
	t.Run("parent deadline cuts off hanging dialer", func(t *testing.T) {
		ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
		defer cancel()

		start := time.Now()
		err := checkKafkaWithDial(ctx, []string{"slow-1:9092", "slow-2:9092"}, func(dialCtx context.Context, _ string) error {
			<-dialCtx.Done()
			return dialCtx.Err()
		})
		elapsed := time.Since(start)

		require.ErrorIs(t, err, context.DeadlineExceeded)
		assert.Less(t, elapsed, 400*time.Millisecond)
	})

	t.Run("timeout on first broker leaves budget for fallback broker", func(t *testing.T) {
		ctx, cancel := context.WithTimeout(context.Background(), 200*time.Millisecond)
		defer cancel()

		var dialed []string
		err := checkKafkaWithDial(ctx, []string{"hang-1:9092", "healthy-2:9092"}, func(dialCtx context.Context, broker string) error {
			dialed = append(dialed, broker)
			if broker == "hang-1:9092" {
				<-dialCtx.Done()
				return dialCtx.Err()
			}
			return nil
		})
		require.NoError(t, err)
		assert.Equal(t, []string{"hang-1:9092", "healthy-2:9092"}, dialed)
	})
}

func TestShutdownServer_MarksNotReadyBeforeDraining(t *testing.T) {
	healthHandler := health.NewHandler()
	healthHandler.SetReady(true)

	inFlightStarted := make(chan struct{})
	releaseInFlight := make(chan struct{})

	mux := http.NewServeMux()
	healthHandler.RegisterRoutes(mux)
	mux.HandleFunc("/ready", healthHandler.ReadyHandler())
	mux.HandleFunc("/work", func(w http.ResponseWriter, _ *http.Request) {
		close(inFlightStarted)
		<-releaseInFlight
		w.WriteHeader(http.StatusOK)
	})

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	require.NoError(t, err)

	server := &http.Server{Handler: mux}
	serveErr := make(chan error, 1)
	go func() {
		serveErr <- server.Serve(ln)
	}()

	reqDone := make(chan int, 1)
	go func() {
		resp, reqErr := http.Get("http://" + ln.Addr().String() + "/work")
		if reqErr != nil {
			reqDone <- -1
			return
		}
		_ = resp.Body.Close()
		reqDone <- resp.StatusCode
	}()

	<-inFlightStarted

	readyReq := httptest.NewRequest(http.MethodGet, "/health/ready", nil)
	readyRR := httptest.NewRecorder()
	healthHandler.ReadyHandler().ServeHTTP(readyRR, readyReq)
	require.Equal(t, http.StatusOK, readyRR.Code)

	shutdownDone := make(chan struct{})
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	go func() {
		shutdownServer(server, healthHandler, 2*time.Second, logger)
		close(shutdownDone)
	}()

	require.Eventually(t, func() bool {
		rr := httptest.NewRecorder()
		healthHandler.ReadyHandler().ServeHTTP(rr, httptest.NewRequest(http.MethodGet, "/health/ready", nil))
		return rr.Code == http.StatusServiceUnavailable
	}, time.Second, 5*time.Millisecond)

	select {
	case <-shutdownDone:
		t.Fatal("server.Shutdown completed before in-flight request finished draining")
	default:
	}

	close(releaseInFlight)
	assert.Equal(t, http.StatusOK, <-reqDone)

	select {
	case <-shutdownDone:
	case <-time.After(2 * time.Second):
		t.Fatal("shutdownServer did not complete after in-flight request drained")
	}

	assert.ErrorIs(t, <-serveErr, http.ErrServerClosed)
}
