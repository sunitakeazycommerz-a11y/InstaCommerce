package health

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"strings"
	"time"
)

const defaultBrokerTimeout = 2 * time.Second

// ShutdownServer marks readiness false and gracefully shuts down the HTTP server within timeout.
func ShutdownServer(server *http.Server, healthHandler *Handler, timeout time.Duration, logger *slog.Logger) {
	if healthHandler != nil {
		healthHandler.SetReady(false)
	}
	if server == nil {
		return
	}
	if logger == nil {
		logger = slog.Default()
	}
	if timeout <= 0 {
		timeout = 5 * time.Second
	}

	shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), timeout)
	defer shutdownCancel()

	if err := server.Shutdown(shutdownCtx); err != nil {
		logger.Error("server shutdown error", "error", err)
		_ = server.Close()
	}
}

// BrokerAttemptContext derives a bounded per-broker context sharing any parent deadline across remainingBrokers.
func BrokerAttemptContext(ctx context.Context, remainingBrokers int, maxBrokerTimeout time.Duration) (context.Context, context.CancelFunc, time.Duration, error) {
	if ctx == nil {
		ctx = context.Background()
	}
	if err := ctx.Err(); err != nil {
		return nil, func() {}, 0, err
	}
	if remainingBrokers <= 0 {
		remainingBrokers = 1
	}
	if maxBrokerTimeout <= 0 {
		maxBrokerTimeout = defaultBrokerTimeout
	}

	budget := maxBrokerTimeout
	if deadline, ok := ctx.Deadline(); ok {
		remaining := time.Until(deadline)
		if remaining <= 0 {
			if err := ctx.Err(); err != nil {
				return nil, func() {}, 0, err
			}
			return nil, func() {}, 0, context.DeadlineExceeded
		}
		if remainingBrokers <= 1 && remaining <= maxBrokerTimeout {
			return ctx, func() {}, remaining, nil
		}
		split := remaining / time.Duration(remainingBrokers)
		if split <= 0 {
			split = remaining
		}
		if split < budget {
			budget = split
		}
	}

	attemptCtx, cancel := context.WithTimeout(ctx, budget)
	return attemptCtx, cancel, budget, nil
}

// CheckTCPBrokers verifies that at least one broker accepts a TCP connection within perBrokerTimeout.
func CheckTCPBrokers(ctx context.Context, brokers []string, perBrokerTimeout time.Duration) error {
	dialer := &net.Dialer{Timeout: perBrokerTimeout}
	return CheckTCPBrokersWithDial(ctx, brokers, perBrokerTimeout, func(dialCtx context.Context, broker string) error {
		conn, err := dialer.DialContext(dialCtx, "tcp", broker)
		if err != nil {
			return err
		}
		return conn.Close()
	})
}

// CheckTCPBrokersWithDial probes brokers sequentially using dial until one succeeds or all fail.
func CheckTCPBrokersWithDial(ctx context.Context, brokers []string, perBrokerTimeout time.Duration, dial func(context.Context, string) error) error {
	if ctx == nil {
		ctx = context.Background()
	}

	validBrokers := make([]string, 0, len(brokers))
	for _, b := range brokers {
		trimmed := strings.TrimSpace(b)
		if trimmed != "" {
			validBrokers = append(validBrokers, trimmed)
		}
	}
	if len(validBrokers) == 0 {
		return errors.New("no brokers configured")
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	if dial == nil {
		return errors.New("dial function is nil")
	}

	var errs []error
	for i, broker := range validBrokers {
		attemptCtx, cancel, _, err := BrokerAttemptContext(ctx, len(validBrokers)-i, perBrokerTimeout)
		if err != nil {
			return err
		}
		err = dial(attemptCtx, broker)
		cancel()
		if err == nil {
			return nil
		}
		errs = append(errs, fmt.Errorf("broker %s: %w", broker, err))
		if ctxErr := ctx.Err(); ctxErr != nil {
			return ctxErr
		}
	}

	return fmt.Errorf("all brokers unreachable: %w", errors.Join(errs...))
}
