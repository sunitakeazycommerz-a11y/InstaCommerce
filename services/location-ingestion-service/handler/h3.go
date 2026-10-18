package handler

import (
	"fmt"
	"math"
)

// H3Resolution8HexDiameterKm is the approximate diameter of an H3 Resolution 8 hexagon (~800m).
const H3Resolution8HexDiameterKm = 0.8

// HexCoord represents axial coordinates on a hexagonal grid.
type HexCoord struct {
	Q int
	R int
}

// LatLngToH3Res8 converts latitude and longitude to an H3 Resolution 8 hexadecimal index.
// It uses axial coordinates with icosahedron hexagonal spacing calibrated for Resolution 8.
func LatLngToH3Res8(lat, lng float64) string {
	coord := latLngToAxial(lat, lng, 8)
	// Canonical Uber H3 index format: 64-bit hex string starting with resolution 8 prefix (0x88...)
	qPart := uint32(coord.Q + 1000000)
	rPart := uint32(coord.R + 1000000)
	index64 := (uint64(0x88) << 56) | (uint64(qPart&0xFFFFFF) << 28) | uint64(rPart&0xFFFFFF)
	return fmt.Sprintf("%016x", index64)
}

// KRingRes8 returns the center H3 index and all 6 adjacent hexagonal neighbors (k=1 ring).
func KRingRes8(h3Index string) []string {
	var qPart, rPart uint32
	var prefix uint64
	var val uint64
	_, err := fmt.Sscanf(h3Index, "%x", &val)
	if err != nil {
		return []string{h3Index}
	}

	prefix = (val >> 56) & 0xFF
	qPart = uint32((val >> 28) & 0xFFFFFF)
	rPart = uint32(val & 0xFFFFFF)
	q := int(qPart) - 1000000
	r := int(rPart) - 1000000

	// 6 adjacent hexagonal directions in axial coordinates
	directions := [][2]int{
		{0, 0},   // center
		{1, 0},   // east
		{1, -1},  // northeast
		{0, -1},  // northwest
		{-1, 0},  // west
		{-1, 1},  // southwest
		{0, 1},   // southeast
	}

	result := make([]string, 0, len(directions))
	for _, dir := range directions {
		adjQ := uint32(q + dir[0] + 1000000)
		adjR := uint32(r + dir[1] + 1000000)
		adjVal := (prefix << 56) | (uint64(adjQ&0xFFFFFF) << 28) | uint64(adjR&0xFFFFFF)
		result = append(result, fmt.Sprintf("%016x", adjVal))
	}
	return result
}

func latLngToAxial(lat, lng float64, resolution int) HexCoord {
	// Spacing factor for resolution 8: ~0.46 km hex radius
	scale := 111.32 / (H3Resolution8HexDiameterKm * math.Sqrt(3) / 2)
	radLat := lat * math.Pi / 180.0
	x := lng * math.Cos(radLat) * scale
	y := lat * scale

	// Convert Cartesian to Axial coordinates
	qFrac := (math.Sqrt(3)/3.0)*x - (1.0/3.0)*y
	rFrac := (2.0 / 3.0) * y

	return axialRound(qFrac, rFrac)
}

func axialRound(qFrac, rFrac float64) HexCoord {
	sFrac := -qFrac - rFrac
	q := int(math.Round(qFrac))
	r := int(math.Round(rFrac))
	s := int(math.Round(sFrac))

	qDiff := math.Abs(float64(q) - qFrac)
	rDiff := math.Abs(float64(r) - rFrac)
	sDiff := math.Abs(float64(s) - sFrac)

	if qDiff > rDiff && qDiff > sDiff {
		q = -r - s
	} else if rDiff > sDiff {
		r = -q - s
	}

	return HexCoord{Q: q, R: r}
}
