package dedup

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"testing"
	"time"

	"github.com/redis/go-redis/v9"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

type mockRedisClient struct {
	mu   sync.Mutex
	data map[string]interface{}
	err  error
}

func newMockRedisClient() *mockRedisClient {
	return &mockRedisClient{
		data: make(map[string]interface{}),
	}
}

func (m *mockRedisClient) SetNX(ctx context.Context, key string, value interface{}, expiration time.Duration) *redis.BoolCmd {
	m.mu.Lock()
	defer m.mu.Unlock()

	cmd := redis.NewBoolCmd(ctx, nil)
	if m.err != nil {
		cmd.SetErr(m.err)
		return cmd
	}

	if _, exists := m.data[key]; !exists {
		m.data[key] = value
		cmd.SetVal(true)
	} else {
		cmd.SetVal(false)
	}
	return cmd
}

func TestCheckerIsDuplicate(t *testing.T) {
	ctx := context.Background()
	mockRedis := newMockRedisClient()
	checker := NewChecker(mockRedis, nil)

	// First occurrence: not duplicate
	isDup, err := checker.IsDuplicate(ctx, "order.events", 0, 1001)
	require.NoError(t, err)
	assert.False(t, isDup, "first message should not be duplicate")

	// Second occurrence: duplicate
	isDup, err = checker.IsDuplicate(ctx, "order.events", 0, 1001)
	require.NoError(t, err)
	assert.True(t, isDup, "identical message offset should be duplicate")

	// Different partition: not duplicate
	isDup, err = checker.IsDuplicate(ctx, "order.events", 1, 1001)
	require.NoError(t, err)
	assert.False(t, isDup, "different partition should not be duplicate")

	// Different topic: not duplicate
	isDup, err = checker.IsDuplicate(ctx, "payment.events", 0, 1001)
	require.NoError(t, err)
	assert.False(t, isDup, "different topic should not be duplicate")
}

func TestCheckerFailOpenOnRedisError(t *testing.T) {
	ctx := context.Background()
	mockRedis := newMockRedisClient()
	mockRedis.err = errors.New("connection refused to redis:6379")

	checker := NewChecker(mockRedis, nil)

	// Fail-open: on Redis error, returns false (not duplicate) to preserve pipeline availability
	isDup, err := checker.IsDuplicate(ctx, "order.events", 0, 500)
	assert.Error(t, err)
	assert.False(t, isDup, "should fail-open when redis fails")
}

func TestCheckerNilRedisClient(t *testing.T) {
	ctx := context.Background()
	checker := NewChecker(nil, nil)

	isDup, err := checker.IsDuplicate(ctx, "order.events", 0, 1)
	require.NoError(t, err)
	assert.False(t, isDup)
}

func TestCheckerWithCustomTTL(t *testing.T) {
	mockRedis := newMockRedisClient()
	checker := NewChecker(mockRedis, nil).WithCustomTTL(12 * time.Hour)
	assert.Equal(t, 12*time.Hour, checker.ttl)
}

func TestCheckerWithBloomFilter(t *testing.T) {
	ctx := context.Background()
	mockRedis := newMockRedisClient()
	bf := NewBloomFilter(1000, 0.01)

	checker := NewChecker(mockRedis, nil).WithBloomFilter(bf)

	isDup, err := checker.IsDuplicate(ctx, "inventory.events", 2, 700)
	require.NoError(t, err)
	assert.False(t, isDup)

	// Bloom filter should have recorded key
	expectedKey := "dedup:inventory.events:2:700"
	assert.True(t, bf.Contains(expectedKey))

	// Recheck should be duplicate
	isDup, err = checker.IsDuplicate(ctx, "inventory.events", 2, 700)
	require.NoError(t, err)
	assert.True(t, isDup)
}

func TestCheckerStandaloneBloomFilterWithoutRedis(t *testing.T) {
	ctx := context.Background()
	bf := NewBloomFilter(500, 0.01)
	checker := NewChecker(nil, nil).WithBloomFilter(bf)

	isDup, err := checker.IsDuplicate(ctx, "rider.events", 0, 42)
	require.NoError(t, err)
	assert.False(t, isDup)

	isDup, err = checker.IsDuplicate(ctx, "rider.events", 0, 42)
	require.NoError(t, err)
	assert.True(t, isDup)
}

func TestCheckerConcurrentDeduplication(t *testing.T) {
	ctx := context.Background()
	mockRedis := newMockRedisClient()
	checker := NewChecker(mockRedis, nil).WithBloomFilter(NewBloomFilter(2000, 0.01))

	var wg sync.WaitGroup
	duplicateCount := 0
	var mu sync.Mutex

	// 50 concurrent attempts for the same message
	for i := 0; i < 50; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			isDup, err := checker.IsDuplicate(ctx, "order.events", 0, 9999)
			if err == nil && isDup {
				mu.Lock()
				duplicateCount++
				mu.Unlock()
			}
		}()
	}
	wg.Wait()

	// Exactly 49 calls should be marked duplicate, exactly 1 allowed
	assert.Equal(t, 49, duplicateCount)
}

func TestBloomFilterBasic(t *testing.T) {
	bf := NewBloomFilter(100, 0.01)
	require.NotNil(t, bf)
	assert.Greater(t, bf.NumBits(), uint64(0))
	assert.Greater(t, bf.NumHashes(), uint(0))

	assert.False(t, bf.Contains("missing_key"))

	bf.Add("key1")
	bf.Add("key2")
	bf.Add("key3")

	assert.True(t, bf.Contains("key1"))
	assert.True(t, bf.Contains("key2"))
	assert.True(t, bf.Contains("key3"))
	assert.False(t, bf.Contains("key4"))

	// Test Reset
	bf.Reset()
	assert.False(t, bf.Contains("key1"))
	assert.False(t, bf.Contains("key2"))
}

func TestBloomFilterFalsePositiveRateWithinBounds(t *testing.T) {
	expectedElements := uint64(1000)
	targetFP := 0.05
	bf := NewBloomFilter(expectedElements, targetFP)

	// Add 1000 elements
	for i := uint64(0); i < expectedElements; i++ {
		bf.Add(fmt.Sprintf("item-%d", i))
	}

	// Verify all 1000 exist (0% false negatives)
	for i := uint64(0); i < expectedElements; i++ {
		assert.True(t, bf.Contains(fmt.Sprintf("item-%d", i)), "false negative detected")
	}

	// Check 2000 distinct items not inserted
	falsePositives := 0
	sampleSize := 2000
	for i := 0; i < sampleSize; i++ {
		if bf.Contains(fmt.Sprintf("non-item-%d", i)) {
			falsePositives++
		}
	}

	observedFPRate := float64(falsePositives) / float64(sampleSize)
	// Observed false positive rate should be reasonably close to target
	assert.LessOrEqual(t, observedFPRate, 0.10, "observed FP rate should be reasonable")
}

func TestBloomFilterConcurrentReadWrite(t *testing.T) {
	bf := NewBloomFilter(1000, 0.01)
	var wg sync.WaitGroup

	for i := 0; i < 20; i++ {
		wg.Add(1)
		idx := i
		go func() {
			defer wg.Done()
			for j := 0; j < 50; j++ {
				key := fmt.Sprintf("goroutine-%d-item-%d", idx, j)
				bf.Add(key)
				_ = bf.Contains(key)
			}
		}()
	}
	wg.Wait()
}
