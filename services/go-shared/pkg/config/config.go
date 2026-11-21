// Package config provides helpers for loading typed configuration values from
// environment variables with defaults, validation, and panic-on-missing
// semantics where appropriate.
//
// All InstaCommerce Go services should use these helpers instead of raw
// os.Getenv calls to ensure consistent behaviour and clear startup failures.
package config

import (
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"
)

// GetEnv returns the value of the environment variable identified by key.
// If the variable is unset or empty, defaultValue is returned.
func GetEnv(key, defaultValue string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return defaultValue
}

// GetEnvTrimmed returns the trimmed value of key, or defaultValue when unset or whitespace.
func GetEnvTrimmed(key, defaultValue string) string {
	if v := strings.TrimSpace(os.Getenv(key)); v != "" {
		return v
	}
	return defaultValue
}

// GetEnvInt returns the environment variable as an int.
// If the variable is unset, empty, or cannot be parsed, defaultValue is returned.
func GetEnvInt(key string, defaultValue int) int {
	v := os.Getenv(key)
	if v == "" {
		return defaultValue
	}
	i, err := strconv.Atoi(v)
	if err != nil {
		return defaultValue
	}
	return i
}

// GetEnvIntStrict returns the parsed integer value of key, or an error when malformed.
func GetEnvIntStrict(key string, defaultValue int) (int, error) {
	v := strings.TrimSpace(os.Getenv(key))
	if v == "" {
		return defaultValue, nil
	}
	parsed, err := strconv.Atoi(v)
	if err != nil {
		return 0, fmt.Errorf("invalid int for %s: %w", key, err)
	}
	return parsed, nil
}

// GetEnvDuration returns the environment variable as a time.Duration
// parsed by time.ParseDuration (e.g. "5s", "100ms", "2m").
// If the variable is unset, empty, or cannot be parsed, defaultValue is returned.
func GetEnvDuration(key string, defaultValue time.Duration) time.Duration {
	v := os.Getenv(key)
	if v == "" {
		return defaultValue
	}
	d, err := time.ParseDuration(v)
	if err != nil {
		return defaultValue
	}
	return d
}

// GetEnvDurationStrict returns the parsed duration value of key, or an error when malformed.
func GetEnvDurationStrict(key string, defaultValue time.Duration) (time.Duration, error) {
	v := strings.TrimSpace(os.Getenv(key))
	if v == "" {
		return defaultValue, nil
	}
	parsed, err := time.ParseDuration(v)
	if err != nil {
		return 0, fmt.Errorf("invalid duration for %s: %w", key, err)
	}
	return parsed, nil
}

// GetEnvBool returns the environment variable as a bool.
// Truthy values: "true", "1", "yes" (case-insensitive).
// If the variable is unset, empty, or cannot be interpreted, defaultValue is returned.
func GetEnvBool(key string, defaultValue bool) bool {
	v := strings.TrimSpace(os.Getenv(key))
	if v == "" {
		return defaultValue
	}
	switch strings.ToLower(v) {
	case "true", "1", "yes":
		return true
	case "false", "0", "no":
		return false
	default:
		return defaultValue
	}
}

// SplitCSV splits comma, semicolon, or space-delimited lists and discards empty elements.
func SplitCSV(value string) []string {
	if strings.TrimSpace(value) == "" {
		return nil
	}
	parts := strings.FieldsFunc(value, func(r rune) bool {
		return r == ',' || r == ';' || r == ' '
	})
	out := make([]string, 0, len(parts))
	for _, part := range parts {
		if trimmed := strings.TrimSpace(part); trimmed != "" {
			out = append(out, trimmed)
		}
	}
	if len(out) == 0 {
		return nil
	}
	return out
}

// GetEnvSlice splits the environment variable by sep and returns the
// resulting slice. Empty elements are discarded. If the variable is unset
// or empty, defaultValue is returned.
func GetEnvSlice(key string, sep string, defaultValue []string) []string {
	v := os.Getenv(key)
	if v == "" {
		return defaultValue
	}
	parts := strings.Split(v, sep)
	result := make([]string, 0, len(parts))
	for _, p := range parts {
		p = strings.TrimSpace(p)
		if p != "" {
			result = append(result, p)
		}
	}
	if len(result) == 0 {
		return defaultValue
	}
	return result
}

// MustGetEnv returns the value of the environment variable identified by key.
// It panics with a descriptive message if the variable is unset or empty,
// ensuring that mandatory configuration is present at startup.
func MustGetEnv(key string) string {
	v := os.Getenv(key)
	if v == "" {
		panic(fmt.Sprintf("required environment variable %s is not set", key))
	}
	return v
}
