package httpclient

import (
	"context"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/instacommerce/go-shared/pkg/auth"
)

type failingReader struct{}

func (failingReader) Read([]byte) (int, error) { return 0, errors.New("read boom") }
func (failingReader) Close() error             { return nil }

func TestClientRetryCountsSingleCircuitBreakerFailurePerLogicalCall(t *testing.T) {
	var hits int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&hits, 1)
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer server.Close()

	client := NewClient(
		WithMaxRetries(3),
		WithBaseDelay(time.Millisecond),
		WithCircuitBreakerThreshold(2),
	)

	_, err := client.Get(context.Background(), server.URL)
	if err == nil {
		t.Fatal("expected error after exhausting retries")
	}
	if got := atomic.LoadInt32(&hits); got != 4 {
		t.Fatalf("expected 4 HTTP attempts, got %d", got)
	}
	if client.circuitBreaker.state != Closed {
		t.Fatalf("expected circuit breaker to remain Closed after 1 logical call (threshold=2), got %v", client.circuitBreaker.state)
	}
	if client.circuitBreaker.failures != 1 {
		t.Fatalf("expected 1 recorded logical failure, got %d", client.circuitBreaker.failures)
	}

	_, err = client.Get(context.Background(), server.URL)
	if err == nil {
		t.Fatal("expected second logical call to fail and trip breaker")
	}
	if client.circuitBreaker.state != Open {
		t.Fatalf("expected circuit breaker to open on 2nd logical failure, got %v", client.circuitBreaker.state)
	}
}

func TestClientHalfOpenProbeAbortDoesNotWedgeCircuitBreaker(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	client := NewClient(
		WithMaxRetries(0),
		WithCircuitBreakerThreshold(1),
		WithCircuitBreakerResetTimeout(15*time.Millisecond),
	)
	client.circuitBreaker.recordFailure()
	time.Sleep(20 * time.Millisecond)

	req, err := http.NewRequestWithContext(context.Background(), http.MethodPost, server.URL, failingReader{})
	if err != nil {
		t.Fatalf("new request: %v", err)
	}
	if _, err := client.Do(context.Background(), req); err == nil {
		t.Fatal("expected body read error")
	}

	resp, err := client.Get(context.Background(), server.URL)
	if err != nil {
		t.Fatalf("expected HalfOpen probe slot to be released after local abort, got %v", err)
	}
	_ = resp.Body.Close()
	if client.circuitBreaker.state != Closed {
		t.Fatalf("expected Closed after successful probe, got %v", client.circuitBreaker.state)
	}
}

func TestClientStatusRetryAndAuthBehavior(t *testing.T) {
	tests := []struct {
		name          string
		responses     []int
		wantStatus    int
		wantAttempts  int32
		wantErr       bool
		internalSvc   string
		internalToken string
		callerHeaders map[string]string
		wantSentSvc   string
		wantSentToken string
	}{
		{
			name:         "retries 429 then succeeds",
			responses:    []int{http.StatusTooManyRequests, http.StatusOK},
			wantStatus:   http.StatusOK,
			wantAttempts: 2,
		},
		{
			name:         "does not retry 400 client error",
			responses:    []int{http.StatusBadRequest, http.StatusOK},
			wantStatus:   http.StatusBadRequest,
			wantAttempts: 1,
		},
		{
			name:          "injects internal auth headers when absent",
			responses:     []int{http.StatusOK},
			wantStatus:    http.StatusOK,
			wantAttempts:  1,
			internalSvc:   "dispatch-optimizer",
			internalToken: "secret-1",
			wantSentSvc:   "dispatch-optimizer",
			wantSentToken: "secret-1",
		},
		{
			name:          "preserves caller internal auth headers",
			responses:     []int{http.StatusOK},
			wantStatus:    http.StatusOK,
			wantAttempts:  1,
			internalSvc:   "dispatch-optimizer",
			internalToken: "secret-1",
			callerHeaders: map[string]string{
				auth.HeaderService: "custom-svc",
				auth.HeaderToken:   "custom-tok",
			},
			wantSentSvc:   "custom-svc",
			wantSentToken: "custom-tok",
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			var attempts int32
			var gotSvc, gotTok string
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				idx := int(atomic.AddInt32(&attempts, 1)) - 1
				gotSvc = r.Header.Get(auth.HeaderService)
				gotTok = r.Header.Get(auth.HeaderToken)
				if idx == 0 && tc.responses[idx] == http.StatusTooManyRequests {
					w.Header().Set("Retry-After", "0")
				}
				w.WriteHeader(tc.responses[idx])
			}))
			defer server.Close()

			opts := []Option{
				WithMaxRetries(2),
				WithBaseDelay(time.Millisecond),
			}
			if tc.internalSvc != "" {
				opts = append(opts, WithInternalAuth(tc.internalSvc, tc.internalToken))
			}
			client := NewClient(opts...)

			req, err := http.NewRequestWithContext(context.Background(), http.MethodPost, server.URL, strings.NewReader(`{"ok":true}`))
			if err != nil {
				t.Fatalf("new request: %v", err)
			}
			for k, v := range tc.callerHeaders {
				req.Header.Set(k, v)
			}

			resp, err := client.Do(context.Background(), req)
			if (err != nil) != tc.wantErr {
				t.Fatalf("err=%v wantErr=%v", err, tc.wantErr)
			}
			if resp != nil {
				_, _ = io.Copy(io.Discard, resp.Body)
				_ = resp.Body.Close()
				if resp.StatusCode != tc.wantStatus {
					t.Fatalf("status=%d want=%d", resp.StatusCode, tc.wantStatus)
				}
			}
			if got := atomic.LoadInt32(&attempts); got != tc.wantAttempts {
				t.Fatalf("attempts=%d want=%d", got, tc.wantAttempts)
			}
			if tc.wantSentSvc != "" && (gotSvc != tc.wantSentSvc || gotTok != tc.wantSentToken) {
				t.Fatalf("sent auth=(%q,%q) want=(%q,%q)", gotSvc, gotTok, tc.wantSentSvc, tc.wantSentToken)
			}
		})
	}
}

func TestDNSCacheCapsActiveEntriesAtMax(t *testing.T) {
	cache := NewDNSCache(time.Minute)
	future := time.Now().Add(time.Minute)
	for i := 0; i < maxDNSCacheEntries; i++ {
		cache.entries[http.StatusText(i)+string(rune('a'+i%26))+string(rune(i))] = dnsEntry{
			ips:       []string{"127.0.0.1"},
			expiresAt: future.Add(time.Duration(i) * time.Millisecond),
		}
	}

	if _, err := cache.LookupHost(context.Background(), "localhost"); err != nil {
		t.Fatalf("LookupHost failed: %v", err)
	}
	if len(cache.entries) > maxDNSCacheEntries {
		t.Fatalf("expected cache size <= %d, got %d", maxDNSCacheEntries, len(cache.entries))
	}
}

func TestClientDeadlineExceededWithRetriesOpensCircuitBreaker(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		select {
		case <-r.Context().Done():
		case <-time.After(250 * time.Millisecond):
			w.WriteHeader(http.StatusOK)
		}
	}))
	defer server.Close()

	client := NewClient(
		WithMaxRetries(2),
		WithBaseDelay(10*time.Millisecond),
		WithCircuitBreakerThreshold(1),
		WithCircuitBreakerResetTimeout(20*time.Millisecond),
	)

	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Millisecond)
	defer cancel()

	_, err := client.Get(ctx, server.URL)
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("expected context.DeadlineExceeded, got %v", err)
	}
	if client.circuitBreaker.failures != 1 {
		t.Fatalf("expected failures=1, got %d", client.circuitBreaker.failures)
	}
	if client.circuitBreaker.state != Open {
		t.Fatalf("expected circuit breaker state Open, got %v", client.circuitBreaker.state)
	}

	time.Sleep(30 * time.Millisecond)

	probeCtx, probeCancel := context.WithTimeout(context.Background(), 20*time.Millisecond)
	defer probeCancel()
	_, err = client.Get(probeCtx, server.URL)
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("expected probe context.DeadlineExceeded, got %v", err)
	}
	if client.circuitBreaker.failures != 2 {
		t.Fatalf("expected failures=2 after half-open probe timeout, got %d", client.circuitBreaker.failures)
	}
	if client.circuitBreaker.state != Open {
		t.Fatalf("expected circuit breaker state Open after failed half-open probe, got %v", client.circuitBreaker.state)
	}
}
