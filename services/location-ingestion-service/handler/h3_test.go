package handler

import (
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestLatLngToH3Res8(t *testing.T) {
	// Bangalore coordinates
	lat := 12.9716
	lng := 77.5946

	index := LatLngToH3Res8(lat, lng)
	require.NotEmpty(t, index)
	assert.Equal(t, 16, len(index))
	// Prefix 88 indicates H3 Resolution 8
	assert.Equal(t, "88", index[:2])

	// Same coordinates must be deterministic
	index2 := LatLngToH3Res8(lat, lng)
	assert.Equal(t, index, index2)

	// Slightly different coordinates nearby should be identical or adjacent
	indexNearby := LatLngToH3Res8(lat+0.0001, lng+0.0001)
	assert.Equal(t, "88", indexNearby[:2])
}

func TestKRingRes8(t *testing.T) {
	lat := 12.9716
	lng := 77.5946
	center := LatLngToH3Res8(lat, lng)

	kRing := KRingRes8(center)
	// k=1 ring on a hexagonal grid contains center + 6 neighbors = 7 cells
	assert.Len(t, kRing, 7)
	assert.Equal(t, center, kRing[0])

	// Ensure all 7 cells are distinct
	seen := make(map[string]bool)
	for _, cell := range kRing {
		assert.False(t, seen[cell], "duplicate cell in k-ring")
		seen[cell] = true
	}
}
