package observability

import (
	"io"
	"log/slog"
	"os"
	"strings"
)

// NewLogger creates a structured *slog.Logger configured for InstaCommerce services.
//
// The level parameter accepts "DEBUG", "INFO", "WARN", or "ERROR" (case-insensitive);
// unrecognised values default to INFO.
//
// When the ENVIRONMENT variable is set to "production" (or unset), the handler
// outputs JSON for structured log aggregation. Any other value uses the human-readable
// text handler for local development.
//
// Default attributes "service_name" and "service_version" are attached to every
// log record from the SERVICE_NAME and SERVICE_VERSION environment variables.
func NewLogger(level string) *slog.Logger {
	lvl := parseLevel(level)
	env := os.Getenv("ENVIRONMENT")

	opts := &slog.HandlerOptions{
		Level:     lvl,
		AddSource: lvl == slog.LevelDebug,
	}

	var handler slog.Handler
	var w io.Writer = os.Stdout

	if strings.EqualFold(env, "development") || strings.EqualFold(env, "local") {
		handler = slog.NewTextHandler(w, opts)
	} else {
		handler = slog.NewJSONHandler(w, opts)
	}

	serviceName := os.Getenv("SERVICE_NAME")
	serviceVersion := os.Getenv("SERVICE_VERSION")

	logger := slog.New(handler)
	if serviceName != "" || serviceVersion != "" {
		logger = logger.With(
			slog.String("service_name", serviceName),
			slog.String("service_version", serviceVersion),
		)
	}

	return logger
}

// parseLevel converts a human-readable level string to slog.Level.
func parseLevel(s string) slog.Level {
	switch strings.ToUpper(strings.TrimSpace(s)) {
	case "DEBUG":
		return slog.LevelDebug
	case "WARN", "WARNING":
		return slog.LevelWarn
	case "ERROR":
		return slog.LevelError
	default:
		return slog.LevelInfo
	}
}
