// Package auth provides internal service-to-service authentication middleware
// for InstaCommerce Go services.
//
// All inter-service HTTP calls must include X-Internal-Service and X-Internal-Token
// headers. This middleware validates those headers against the expected token
// configured via the INTERNAL_SERVICE_TOKEN environment variable.
package auth

import (
	"crypto/subtle"
	"log/slog"
	"net/http"
	"os"
)

const (
	// HeaderService identifies the calling service.
	HeaderService = "X-Internal-Service"
	// HeaderToken carries the shared authentication token.
	HeaderToken = "X-Internal-Token"
)

// InternalAuthMiddleware validates internal service-to-service requests
// by checking the X-Internal-Service and X-Internal-Token headers.
// Paths in skipPaths (e.g. /health, /metrics) bypass authentication.
type InternalAuthMiddleware struct {
	expectedToken string
	serviceName   string
	logger        *slog.Logger
	skipPaths     map[string]bool
}

// NewInternalAuthMiddleware creates a middleware instance configured from
// environment variables:
//   - INTERNAL_SERVICE_TOKEN: the shared secret all services must present
//   - INTERNAL_SERVICE_NAME: the name of this service (used in logs)
//
// Health and metrics endpoints are excluded from authentication by default.
func NewInternalAuthMiddleware(logger *slog.Logger) *InternalAuthMiddleware {
	return &InternalAuthMiddleware{
		expectedToken: os.Getenv("INTERNAL_SERVICE_TOKEN"),
		serviceName:   os.Getenv("INTERNAL_SERVICE_NAME"),
		logger:        logger,
		skipPaths: map[string]bool{
			"/health":       true,
			"/health/ready": true,
			"/health/live":  true,
			"/metrics":      true,
		},
	}
}

// Wrap returns an http.Handler that enforces internal authentication.
// Requests to skip paths are forwarded without checks.
// Missing or invalid credentials result in 401 or 403 responses respectively.
func (m *InternalAuthMiddleware) Wrap(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Skip authentication for health and metrics endpoints.
		if m.skipPaths[r.URL.Path] {
			next.ServeHTTP(w, r)
			return
		}

		callingService := r.Header.Get(HeaderService)
		token := r.Header.Get(HeaderToken)

		if callingService == "" || token == "" {
			m.logger.Warn("missing authentication headers",
				slog.String("path", r.URL.Path),
				slog.String("remote_addr", r.RemoteAddr),
				slog.String("calling_service", callingService),
			)
			http.Error(w, `{"error":"missing authentication headers"}`, http.StatusUnauthorized)
			return
		}

		// Constant-time comparison to prevent timing attacks.
		if subtle.ConstantTimeCompare([]byte(token), []byte(m.expectedToken)) != 1 {
			m.logger.Warn("invalid service token",
				slog.String("path", r.URL.Path),
				slog.String("remote_addr", r.RemoteAddr),
				slog.String("calling_service", callingService),
			)
			http.Error(w, `{"error":"forbidden"}`, http.StatusForbidden)
			return
		}

		m.logger.Debug("authenticated internal request",
			slog.String("calling_service", callingService),
			slog.String("path", r.URL.Path),
		)

		next.ServeHTTP(w, r)
	})
}
