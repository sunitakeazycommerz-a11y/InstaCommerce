package dedup

import (
	"math"
	"sync"

	"github.com/cespare/xxhash/v2"
)

// BloomFilter is a concurrent-safe in-memory Bloom filter for probabilistic set membership.
// It is used for fast-path deduplication checks before hitting distributed Redis storage.
type BloomFilter struct {
	mu        sync.RWMutex
	bitset    []uint64
	numBits   uint64
	numHashes uint
}

// NewBloomFilter creates a Bloom filter optimized for the expected number of elements
// and desired false positive probability.
func NewBloomFilter(expectedElements uint64, falsePositiveRate float64) *BloomFilter {
	if expectedElements == 0 {
		expectedElements = 10000
	}
	if falsePositiveRate <= 0 || falsePositiveRate >= 1.0 {
		falsePositiveRate = 0.01 // default 1% FP rate
	}

	// Optimal m = - (n * ln(p)) / (ln(2)^2)
	ln2 := math.Ln2
	m := uint64(math.Ceil(-float64(expectedElements) * math.Log(falsePositiveRate) / (ln2 * ln2)))
	if m == 0 {
		m = 64
	}

	// Optimal k = (m / n) * ln(2)
	k := uint(math.Round(float64(m) / float64(expectedElements) * ln2))
	if k == 0 {
		k = 1
	}

	// Allocate 64-bit word chunks
	numWords := (m + 63) / 64
	return &BloomFilter{
		bitset:    make([]uint64, numWords),
		numBits:   numWords * 64,
		numHashes: k,
	}
}

// hashPair generates two independent 64-bit hashes using xxhash.
func hashPair(data []byte) (uint64, uint64) {
	v1 := xxhash.Sum64(data)
	salted := append([]byte{0x5A, 0xC3}, data...)
	v2 := xxhash.Sum64(salted)
	if v2 == 0 {
		v2 = 1
	}
	return v1, v2
}

// Add inserts a key into the Bloom filter.
func (bf *BloomFilter) Add(key string) {
	bf.mu.Lock()
	defer bf.mu.Unlock()

	h1, h2 := hashPair([]byte(key))
	for i := uint(0); i < bf.numHashes; i++ {
		combined := (h1 + uint64(i)*h2) % bf.numBits
		wordIndex := combined / 64
		bitIndex := combined % 64
		bf.bitset[wordIndex] |= 1 << bitIndex
	}
}

// Contains checks if the key has likely been added to the Bloom filter.
// False negative rate is strictly 0%. False positive rate is bounded by design.
func (bf *BloomFilter) Contains(key string) bool {
	bf.mu.RLock()
	defer bf.mu.RUnlock()

	h1, h2 := hashPair([]byte(key))
	for i := uint(0); i < bf.numHashes; i++ {
		combined := (h1 + uint64(i)*h2) % bf.numBits
		wordIndex := combined / 64
		bitIndex := combined % 64
		if (bf.bitset[wordIndex] & (1 << bitIndex)) == 0 {
			return false
		}
	}
	return true
}

// Reset clears all bits in the Bloom filter for window rotation.
func (bf *BloomFilter) Reset() {
	bf.mu.Lock()
	defer bf.mu.Unlock()

	for i := range bf.bitset {
		bf.bitset[i] = 0
	}
}

// NumBits returns the total bitset capacity.
func (bf *BloomFilter) NumBits() uint64 {
	return bf.numBits
}

// NumHashes returns the number of hash iterations.
func (bf *BloomFilter) NumHashes() uint {
	return bf.numHashes
}
