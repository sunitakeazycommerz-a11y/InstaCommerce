package config

import (
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestGetEnvTrimmedAndSplitCSV(t *testing.T) {
	t.Setenv("TEST_TRIMMED", "  val-1  ")
	t.Setenv("TEST_BLANK", "   ")
	assert.Equal(t, "val-1", GetEnvTrimmed("TEST_TRIMMED", "fallback"))
	assert.Equal(t, "fallback", GetEnvTrimmed("TEST_BLANK", "fallback"))
	assert.Equal(t, "fallback", GetEnvTrimmed("TEST_UNSET", "fallback"))

	assert.Nil(t, SplitCSV("   "))
	assert.Equal(t, []string{"a", "b", "c", "d"}, SplitCSV(" a , b;c d "))
}

func TestGetEnvStrictHelpers(t *testing.T) {
	t.Setenv("TEST_INT_OK", " 42 ")
	t.Setenv("TEST_INT_BAD", "abc")
	t.Setenv("TEST_DUR_OK", " 15s ")
	t.Setenv("TEST_DUR_BAD", "nope")

	v, err := GetEnvIntStrict("TEST_INT_OK", 10)
	require.NoError(t, err)
	assert.Equal(t, 42, v)

	v, err = GetEnvIntStrict("TEST_INT_UNSET", 10)
	require.NoError(t, err)
	assert.Equal(t, 10, v)

	_, err = GetEnvIntStrict("TEST_INT_BAD", 10)
	require.Error(t, err)

	d, err := GetEnvDurationStrict("TEST_DUR_OK", time.Second)
	require.NoError(t, err)
	assert.Equal(t, 15*time.Second, d)

	d, err = GetEnvDurationStrict("TEST_DUR_UNSET", time.Second)
	require.NoError(t, err)
	assert.Equal(t, time.Second, d)

	_, err = GetEnvDurationStrict("TEST_DUR_BAD", time.Second)
	require.Error(t, err)
}
