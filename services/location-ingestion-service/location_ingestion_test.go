package main

import (
	"encoding/json"
	"math"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestLocationEventIngestionWithValidCoordinates(t *testing.T) {
	// Test that valid location events are accepted
	input := LocationInput{
		RiderID: "rider-123",
		Lat:     12.9716,
		Lng:     77.5946,
	}

	update, err := normalizeLocation(input)
	require.NoError(t, err)

	assert.Equal(t, "rider-123", update.RiderID)
	assert.Equal(t, 12.9716, update.Lat)
	assert.Equal(t, 77.5946, update.Lng)
	assert.NotZero(t, update.Timestamp)
}

func TestLocationEventWithCompleteMetadata(t *testing.T) {
	// Test event ingestion with speed, heading, and accuracy
	now := time.Now().UnixMilli()
	speed := 25.5
	heading := 180.0
	accuracy := 5.0

	input := LocationInput{
		RiderID:   "rider-456",
		Lat:       12.9750,
		Lng:       77.6000,
		Timestamp: &now,
		Speed:     &speed,
		Heading:   &heading,
		Accuracy:  &accuracy,
	}

	update, err := normalizeLocation(input)
	require.NoError(t, err)

	assert.Equal(t, "rider-456", update.RiderID)
	assert.NotNil(t, update.Speed)
	assert.Equal(t, 25.5, *update.Speed)
	assert.NotNil(t, update.Heading)
	assert.Equal(t, 180.0, *update.Heading)
	assert.NotNil(t, update.Accuracy)
	assert.Equal(t, 5.0, *update.Accuracy)
}

func TestLocationValidationRejectsInvalidLatitude(t *testing.T) {
	// Test that invalid latitude values are rejected
	invalidLatitudes := []float64{-91.0, 91.0, 120.0, -120.0}

	for _, lat := range invalidLatitudes {
		input := LocationInput{
			RiderID: "rider-123",
			Lat:     lat,
			Lng:     77.5946,
		}

		_, err := normalizeLocation(input)
		assert.Error(t, err, "latitude %v should be rejected", lat)
	}
}

func TestLocationValidationRejectsInvalidLongitude(t *testing.T) {
	// Test that invalid longitude values are rejected
	invalidLongitudes := []float64{-181.0, 181.0, 200.0, -200.0}

	for _, lng := range invalidLongitudes {
		input := LocationInput{
			RiderID: "rider-123",
			Lat:     12.9716,
			Lng:     lng,
		}

		_, err := normalizeLocation(input)
		assert.Error(t, err, "longitude %v should be rejected", lng)
	}
}

func TestLocationValidationRejectsMissingRiderID(t *testing.T) {
	// Test that events without rider ID are rejected
	input := LocationInput{
		RiderID: "",
		Lat:     12.9716,
		Lng:     77.5946,
	}

	_, err := normalizeLocation(input)
	assert.Error(t, err)
}

func TestLocationValidationRejectsNegativeSpeed(t *testing.T) {
	// Test that negative speed values are rejected
	negativeSpeed := -10.0

	input := LocationInput{
		RiderID: "rider-123",
		Lat:     12.9716,
		Lng:     77.5946,
		Speed:   &negativeSpeed,
	}

	_, err := normalizeLocation(input)
	assert.Error(t, err)
}

func TestLocationValidationRejectsInvalidHeading(t *testing.T) {
	// Test that invalid heading values are rejected
	invalidHeadings := []float64{-1.0, 360.0, 400.0}

	for _, heading := range invalidHeadings {
		input := LocationInput{
			RiderID: "rider-123",
			Lat:     12.9716,
			Lng:     77.5946,
			Heading: &heading,
		}

		_, err := normalizeLocation(input)
		assert.Error(t, err, "heading %v should be rejected", heading)
	}
}

func TestLocationValidationRejectsNegativeAccuracy(t *testing.T) {
	// Test that negative accuracy values are rejected
	negativeAccuracy := -1.0

	input := LocationInput{
		RiderID:  "rider-123",
		Lat:      12.9716,
		Lng:      77.5946,
		Accuracy: &negativeAccuracy,
	}

	_, err := normalizeLocation(input)
	assert.Error(t, err)
}

func TestLocationEventGeographicFiltering(t *testing.T) {
	// Test that locations in valid geographic bounds are processed
	validRegions := []struct {
		name string
		lat  float64
		lng  float64
	}{
		{"North Pole", 90.0, 0.0},
		{"South Pole", -90.0, 0.0},
		{"Equator Prime Meridian", 0.0, 0.0},
		{"Prime Meridian", 0.0, 0.0},
		{"Date Line", 0.0, 180.0},
		{"Date Line West", 0.0, -180.0},
		{"India", 12.9716, 77.5946},
		{"India East", 28.7041, 77.1025},
	}

	for _, region := range validRegions {
		t.Run(region.name, func(t *testing.T) {
			input := LocationInput{
				RiderID: "rider-123",
				Lat:     region.lat,
				Lng:     region.lng,
			}

			update, err := normalizeLocation(input)
			require.NoError(t, err)
			assert.Equal(t, region.lat, update.Lat)
			assert.Equal(t, region.lng, update.Lng)
		})
	}
}

func TestLocationEventTimestampHandling(t *testing.T) {
	// Test that timestamp is properly normalized
	testCases := []struct {
		name          string
		inputTimestamp *int64
		expectedValid bool
	}{
		{
			name:            "Valid timestamp",
			inputTimestamp: func() *int64 { ts := time.Now().UnixMilli(); return &ts }(),
			expectedValid:   true,
		},
		{
			name:            "Nil timestamp uses current time",
			inputTimestamp: nil,
			expectedValid:   true,
		},
		{
			name:            "Zero timestamp uses current time",
			inputTimestamp: func() *int64 { var ts int64 = 0; return &ts }(),
			expectedValid:   true,
		},
		{
			name:            "Past timestamp is valid",
			inputTimestamp: func() *int64 { ts := time.Now().AddDate(-1, 0, 0).UnixMilli(); return &ts }(),
			expectedValid:   true,
		},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			input := LocationInput{
				RiderID:   "rider-123",
				Lat:       12.9716,
				Lng:       77.5946,
				Timestamp: tc.inputTimestamp,
			}

			update, err := normalizeLocation(input)
			if tc.expectedValid {
				require.NoError(t, err)
				assert.NotZero(t, update.Timestamp)
			}
		})
	}
}

func TestLocationEventJSONSerialization(t *testing.T) {
	// Test that location events serialize/deserialize correctly
	now := time.Now().UnixMilli()
	speed := 25.5
	heading := 90.0

	original := LocationInput{
		RiderID:   "rider-789",
		Lat:       13.0249,
		Lng:       77.5937,
		Timestamp: &now,
		Speed:     &speed,
		Heading:   &heading,
	}

	// Serialize
	data, err := json.Marshal(original)
	require.NoError(t, err)

	// Deserialize
	var deserialized LocationInput
	err = json.Unmarshal(data, &deserialized)
	require.NoError(t, err)

	assert.Equal(t, original.RiderID, deserialized.RiderID)
	assert.Equal(t, original.Lat, deserialized.Lat)
	assert.Equal(t, original.Lng, deserialized.Lng)
	assert.Equal(t, *original.Speed, *deserialized.Speed)
	assert.Equal(t, *original.Heading, *deserialized.Heading)
}

func TestLocationEventBoundaryValues(t *testing.T) {
	// Test boundary coordinates
	tests := []struct {
		name  string
		lat   float64
		lng   float64
		valid bool
	}{
		{"Max latitude", 90.0, 77.5946, true},
		{"Min latitude", -90.0, 77.5946, true},
		{"Max longitude", 12.9716, 180.0, true},
		{"Min longitude", 12.9716, -180.0, true},
		{"Over max latitude", 90.1, 77.5946, false},
		{"Under min latitude", -90.1, 77.5946, false},
		{"Over max longitude", 12.9716, 180.1, false},
		{"Under min longitude", 12.9716, -180.1, false},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			input := LocationInput{
				RiderID: "rider-123",
				Lat:     tt.lat,
				Lng:     tt.lng,
			}

			_, err := normalizeLocation(input)
			if tt.valid {
				assert.NoError(t, err)
			} else {
				assert.Error(t, err)
			}
		})
	}
}

func TestLocationHeadingBoundaryValues(t *testing.T) {
	// Test heading boundary values
	tests := []struct {
		name  string
		heading float64
		valid bool
	}{
		{"Heading 0", 0.0, true},
		{"Heading 90", 90.0, true},
		{"Heading 180", 180.0, true},
		{"Heading 270", 270.0, true},
		{"Heading 359", 359.99, true},
		{"Heading 360", 360.0, false},
		{"Negative heading", -1.0, false},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			heading := tt.heading
			input := LocationInput{
				RiderID: "rider-123",
				Lat:     12.9716,
				Lng:     77.5946,
				Heading: &heading,
			}

			_, err := normalizeLocation(input)
			if tt.valid {
				assert.NoError(t, err)
			} else {
				assert.Error(t, err)
			}
		})
	}
}

func TestLocationSpeedBoundaryValues(t *testing.T) {
	// Test that speed boundaries are enforced
	tests := []struct {
		name  string
		speed float64
		valid bool
	}{
		{"Speed 0", 0.0, true},
		{"Low speed", 5.0, true},
		{"High speed", 120.0, true},
		{"Realistic max", 200.0, true},
		{"Negative speed", -1.0, false},
		{"Very large speed", math.MaxFloat64, true},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			speed := tt.speed
			input := LocationInput{
				RiderID: "rider-123",
				Lat:     12.9716,
				Lng:     77.5946,
				Speed:   &speed,
			}

			_, err := normalizeLocation(input)
			if tt.valid {
				assert.NoError(t, err)
			} else {
				assert.Error(t, err)
			}
		})
	}
}
