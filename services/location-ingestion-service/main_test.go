package main

import (
	"bytes"
	"context"
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/gorilla/websocket"
	"github.com/instacommerce/go-shared/pkg/health"
	"github.com/instacommerce/location-ingestion-service/handler"
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

type stubGeofencePublisher struct {
	mu     sync.Mutex
	events []handler.GeofenceEventEnvelope
}

func (s *stubGeofencePublisher) PublishGeofenceEvent(_ context.Context, env handler.GeofenceEventEnvelope) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.events = append(s.events, env)
	return nil
}

func TestLoadConfig_RedisTTLDefault10MinutesWhenNonPositive(t *testing.T) {
	t.Setenv("REDIS_TTL", "0s")
	cfg := loadConfig()
	assert.Equal(t, 10*time.Minute, cfg.RedisTTL)

	t.Setenv("REDIS_TTL", "-5s")
	cfg = loadConfig()
	assert.Equal(t, 10*time.Minute, cfg.RedisTTL)
}

func TestNewHTTPMux_EnforcesInternalAuthOnIngestAndBatch(t *testing.T) {
	t.Setenv("INTERNAL_SERVICE_TOKEN", "secret-loc-token")
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	service := &IngestService{
		cfg:    Config{RedisTTL: 10 * time.Minute, MaxBodyBytes: defaultMaxBodyBytes, ProcessTimeout: time.Second},
		logger: logger,
	}
	healthHandler := health.NewHandler()
	healthHandler.SetReady(true)
	mux := newHTTPMux(service, healthHandler, logger)

	for _, path := range []string{"/ingest", "/ingest/location", "/ingest/batch", "/batch"} {
		body := `{"rider_id":"r-1","lat":12.9716,"lng":77.5946}`
		if strings.Contains(path, "batch") {
			body = `[{"rider_id":"r-1","lat":12.9716,"lng":77.5946}]`
		}
		// Unauthenticated -> 401
		req := httptest.NewRequest(http.MethodPost, path, bytes.NewBufferString(body))
		rec := httptest.NewRecorder()
		mux.ServeHTTP(rec, req)
		assert.Equal(t, http.StatusUnauthorized, rec.Code, "path %s must require auth", path)

		// Authenticated -> 202 Accepted
		reqAuth := httptest.NewRequest(http.MethodPost, path, bytes.NewBufferString(body))
		reqAuth.Header.Set("X-Internal-Service", "api-gateway")
		reqAuth.Header.Set("X-Internal-Token", "secret-loc-token")
		recAuth := httptest.NewRecorder()
		mux.ServeHTTP(recAuth, reqAuth)
		assert.Equal(t, http.StatusAccepted, recAuth.Code, "authenticated request to %s should succeed", path)
	}
}

func TestProcessInput_PublishesGeofenceTransitions(t *testing.T) {
	geofencer := handler.NewGeofenceChecker(8)
	geofencer.AddZone(handler.Zone{
		ID:        "store-1",
		Type:      handler.ZoneStore,
		CenterLat: 12.9716,
		CenterLng: 77.5946,
		RadiusKm:  0.2,
	})
	pub := &stubGeofencePublisher{}
	service := &IngestService{
		cfg:         Config{ProcessTimeout: time.Second},
		logger:      slog.New(slog.NewTextHandler(io.Discard, nil)),
		geofencer:   geofencer,
		geofencePub: pub,
	}

	err := service.processInput(context.Background(), LocationInput{
		RiderID: "rider-geo-1",
		Lat:     12.9716,
		Lng:     77.5946,
	}, "http")
	require.NoError(t, err)

	pub.mu.Lock()
	defer pub.mu.Unlock()
	require.Len(t, pub.events, 1)
	assert.Equal(t, "RiderGeofenceTransition", pub.events[0].EventType)
	assert.Equal(t, "store-1", pub.events[0].Payload.ZoneID)
	assert.Equal(t, "ENTERED_STORE", pub.events[0].Payload.EventType)
}

func TestCloseWebSockets_WaitsForActiveConnectionGoroutines(t *testing.T) {
	t.Setenv("INTERNAL_SERVICE_TOKEN", "ws-secret")
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	service := &IngestService{
		cfg:     Config{WebSocketReadMax: 1 << 20, ProcessTimeout: time.Second},
		logger:  logger,
		wsConns: make(map[*websocket.Conn]struct{}),
	}
	hh := health.NewHandler()
	hh.SetReady(true)
	srv := httptest.NewServer(newHTTPMux(service, hh, logger))
	defer srv.Close()

	wsURL := "ws" + strings.TrimPrefix(srv.URL, "http") + "/ingest/ws"
	hdr := http.Header{}
	hdr.Set("X-Internal-Service", "mobile-bff")
	hdr.Set("X-Internal-Token", "ws-secret")
	conn, _, err := websocket.DefaultDialer.Dial(wsURL, hdr)
	require.NoError(t, err)
	defer conn.Close()

	require.Eventually(t, func() bool {
		service.wsMu.Lock()
		defer service.wsMu.Unlock()
		return len(service.wsConns) == 1
	}, time.Second, 5*time.Millisecond)

	service.CloseWebSockets()

	service.wsMu.Lock()
	remaining := len(service.wsConns)
	service.wsMu.Unlock()
	assert.Equal(t, 0, remaining)
}
