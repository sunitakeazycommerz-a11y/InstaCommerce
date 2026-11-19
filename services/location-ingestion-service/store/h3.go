package store

import (
	"fmt"
	"math"
)

const earthRadiusKm = 6371.0

func latLngToH3Res8(lat, lng float64) string {
	scale := 111.32 / (H3Resolution8HexDiameterKm * math.Sqrt(3) / 2)
	radLat := lat * math.Pi / 180.0
	x := lng * math.Cos(radLat) * scale
	y := lat * scale

	qFrac := (math.Sqrt(3)/3.0)*x - (1.0/3.0)*y
	rFrac := (2.0 / 3.0) * y
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

	qPart := uint32(q + 1000000)
	rPart := uint32(r + 1000000)
	index64 := (uint64(0x88) << 56) | (uint64(qPart&0xFFFFFF) << 28) | uint64(rPart&0xFFFFFF)
	return fmt.Sprintf("%016x", index64)
}

func kRingRes8(h3Index string) []string {
	var val uint64
	if _, err := fmt.Sscanf(h3Index, "%x", &val); err != nil {
		return []string{h3Index}
	}

	prefix := (val >> 56) & 0xFF
	qPart := uint32((val >> 28) & 0xFFFFFF)
	rPart := uint32(val & 0xFFFFFF)
	q := int(qPart) - 1000000
	r := int(rPart) - 1000000

	directions := [][2]int{
		{0, 0}, {1, 0}, {1, -1}, {0, -1}, {-1, 0}, {-1, 1}, {0, 1},
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

func haversineKm(lat1, lng1, lat2, lng2 float64) float64 {
	dLat := degToRad(lat2 - lat1)
	dLng := degToRad(lng2 - lng1)

	a := math.Sin(dLat/2)*math.Sin(dLat/2) +
		math.Cos(degToRad(lat1))*math.Cos(degToRad(lat2))*
			math.Sin(dLng/2)*math.Sin(dLng/2)
	if a < 0 {
		a = 0
	} else if a > 1 {
		a = 1
	}

	c := 2 * math.Atan2(math.Sqrt(a), math.Sqrt(1-a))
	return earthRadiusKm * c
}

func degToRad(deg float64) float64 {
	return deg * math.Pi / 180
}
