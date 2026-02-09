package main

import (
	"encoding/json"
	"errors"
	"io"
	"log"
	"math"
	"net/http"
	"os"
	"sort"
	"time"
)

const maxBodyBytes = 1 << 20

type Position struct {
	Lat float64 `json:"lat"`
	Lng float64 `json:"lng"`
}

type Rider struct {
	ID       string   `json:"id"`
	Position Position `json:"position"`
}

type Order struct {
	ID       string   `json:"id"`
	Position Position `json:"position"`
}

type AssignRequest struct {
	Riders   []Rider `json:"riders"`
	Orders   []Order `json:"orders"`
	Capacity int     `json:"capacity"`
}

type Assignment struct {
	RiderID       string   `json:"rider_id"`
	OrderIDs      []string `json:"order_ids"`
	TotalDistance float64  `json:"total_distance"`
}

type AssignResponse struct {
	Assignments     []Assignment `json:"assignments"`
	UnassignedOrders []string    `json:"unassigned_orders"`
}

type errorResponse struct {
	Error string `json:"error"`
}

func main() {
	port := os.Getenv("PORT")
	if port == "" {
		port = os.Getenv("SERVER_PORT")
	}
	if port == "" {
		port = "8080"
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/health", handleHealth)
	mux.HandleFunc("/optimize/assign", handleAssign)

	server := &http.Server{
		Addr:              ":" + port,
		Handler:           mux,
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      15 * time.Second,
		IdleTimeout:       60 * time.Second,
	}

	log.Printf("dispatch optimizer service listening on %s", server.Addr)
	if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Fatalf("server failed: %v", err)
	}
}

func handleHealth(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		w.Header().Set("Allow", "GET, HEAD")
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

func handleAssign(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", "POST")
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}

	r.Body = http.MaxBytesReader(w, r.Body, maxBodyBytes)
	decoder := json.NewDecoder(r.Body)
	decoder.DisallowUnknownFields()

	var req AssignRequest
	if err := decoder.Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	if err := decoder.Decode(&struct{}{}); err != io.EOF {
		writeError(w, http.StatusBadRequest, "request body must contain a single JSON object")
		return
	}
	if err := validateRequest(req); err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}

	assignments, unassigned := optimizeAssignments(req.Riders, req.Orders, req.Capacity)
	writeJSON(w, http.StatusOK, AssignResponse{
		Assignments:     assignments,
		UnassignedOrders: unassigned,
	})
}

func validateRequest(req AssignRequest) error {
	if req.Capacity <= 0 {
		return errors.New("capacity must be greater than 0")
	}

	riderIDs := make(map[string]struct{}, len(req.Riders))
	for _, rider := range req.Riders {
		if rider.ID == "" {
			return errors.New("rider id is required")
		}
		if _, exists := riderIDs[rider.ID]; exists {
			return errors.New("rider ids must be unique")
		}
		riderIDs[rider.ID] = struct{}{}
	}

	orderIDs := make(map[string]struct{}, len(req.Orders))
	for _, order := range req.Orders {
		if order.ID == "" {
			return errors.New("order id is required")
		}
		if _, exists := orderIDs[order.ID]; exists {
			return errors.New("order ids must be unique")
		}
		orderIDs[order.ID] = struct{}{}
	}

	return nil
}

func optimizeAssignments(riders []Rider, orders []Order, capacity int) ([]Assignment, []string) {
	assigned := make(map[string]bool, len(orders))
	assignments := make([]Assignment, 0, len(riders))

	for _, rider := range riders {
		current := rider.Position
		orderIDs := make([]string, 0, capacity)
		totalDistance := 0.0

		for len(orderIDs) < capacity {
			nearestIndex := -1
			nearestDistance := math.MaxFloat64
			nearestID := ""

			for i, order := range orders {
				if assigned[order.ID] {
					continue
				}
				distance := distance(current, order.Position)
				if distance < nearestDistance || (math.Abs(distance-nearestDistance) < 1e-9 && (nearestID == "" || order.ID < nearestID)) {
					nearestDistance = distance
					nearestIndex = i
					nearestID = order.ID
				}
			}

			if nearestIndex == -1 {
				break
			}

			orderIDs = append(orderIDs, orders[nearestIndex].ID)
			totalDistance += nearestDistance
			current = orders[nearestIndex].Position
			assigned[orders[nearestIndex].ID] = true
		}

		assignments = append(assignments, Assignment{
			RiderID:       rider.ID,
			OrderIDs:      orderIDs,
			TotalDistance: totalDistance,
		})
	}

	unassigned := make([]string, 0)
	for _, order := range orders {
		if !assigned[order.ID] {
			unassigned = append(unassigned, order.ID)
		}
	}
	sort.Strings(unassigned)

	return assignments, unassigned
}

func distance(a, b Position) float64 {
	return math.Hypot(a.Lat-b.Lat, a.Lng-b.Lng)
}

func writeJSON(w http.ResponseWriter, status int, payload any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	encoder := json.NewEncoder(w)
	encoder.SetEscapeHTML(false)
	_ = encoder.Encode(payload)
}

func writeError(w http.ResponseWriter, status int, message string) {
	writeJSON(w, status, errorResponse{Error: message})
}
