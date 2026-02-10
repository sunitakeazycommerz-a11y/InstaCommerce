package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"math"
	"net/http"
	"os"
	"os/signal"
	"sort"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promhttp"
	"go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracegrpc"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	semconv "go.opentelemetry.io/otel/semconv/v1.24.0"
	oteltrace "go.opentelemetry.io/otel/trace"
)

const (
	maxBodyBytes = 1 << 20
	serviceName  = "dispatch-optimizer-service"
)

type Position struct {
	Lat float64 `json:"lat"`
	Lng float64 `json:"lng"`
}

type Rider struct {
	ID       string   `json:"id"`
	Position Position `json:"position"`
	Zone     string   `json:"zone,omitempty"`
}

type Order struct {
	ID       string   `json:"id"`
	Position Position `json:"position"`
	Zone     string   `json:"zone,omitempty"`
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

type Constraint interface {
	Name() string
	Allows(rider Rider, order Order, assigned []Order) bool
}

type ConstraintSet []Constraint

func (cs ConstraintSet) Allows(rider Rider, order Order, assigned []Order) bool {
	for _, constraint := range cs {
		if !constraint.Allows(rider, order, assigned) {
			return false
		}
	}
	return true
}

func (cs ConstraintSet) Names() []string {
	names := make([]string, 0, len(cs))
	for _, constraint := range cs {
		names = append(names, constraint.Name())
	}
	return names
}

type CapacityConstraint struct {
	Max int
}

func (c CapacityConstraint) Name() string {
	return "capacity"
}

func (c CapacityConstraint) Allows(_ Rider, _ Order, assigned []Order) bool {
	return len(assigned) < c.Max
}

type ZoneConstraint struct{}

func (ZoneConstraint) Name() string {
	return "zone"
}

func (ZoneConstraint) Allows(rider Rider, order Order, _ []Order) bool {
	if rider.Zone == "" || order.Zone == "" {
		return true
	}
	return rider.Zone == order.Zone
}

type constraintFactory func(req AssignRequest) Constraint

var constraintFactories = map[string]constraintFactory{
	"capacity": func(req AssignRequest) Constraint {
		return CapacityConstraint{Max: req.Capacity}
	},
	"zone": func(AssignRequest) Constraint {
		return ZoneConstraint{}
	},
}

type serviceMetrics struct {
	requestCount     *prometheus.CounterVec
	requestDuration  *prometheus.HistogramVec
	optimizeDuration prometheus.Histogram
	assignedOrders   prometheus.Counter
	unassignedOrders prometheus.Counter
}

type responseRecorder struct {
	http.ResponseWriter
	status int
	bytes  int
}

func (r *responseRecorder) WriteHeader(status int) {
	r.status = status
	r.ResponseWriter.WriteHeader(status)
}

func (r *responseRecorder) Write(body []byte) (int, error) {
	if r.status == 0 {
		r.status = http.StatusOK
	}
	written, err := r.ResponseWriter.Write(body)
	r.bytes += written
	return written, err
}

func main() {
	logger := initLogger()
	slog.SetDefault(logger)
	shutdownTracer := initTracerProvider(context.Background(), logger)
	metrics := newMetrics()

	port := os.Getenv("PORT")
	if port == "" {
		port = os.Getenv("SERVER_PORT")
	}
	if port == "" {
		port = "8102"
	}

	mux := http.NewServeMux()
	healthHandler := otelhttp.NewHandler(
		instrumentHandler("health", logger, metrics, http.HandlerFunc(handleHealth)),
		"Health",
	)
	mux.Handle("/health", healthHandler)
	mux.Handle("/health/ready", healthHandler)
	mux.Handle("/health/live", healthHandler)
	mux.Handle("/metrics", promhttp.Handler())
	assignHandler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		handleAssign(w, r, logger, metrics)
	})
	mux.Handle("/optimize/assign", otelhttp.NewHandler(
		instrumentHandler("optimize_assign", logger, metrics, assignHandler),
		"OptimizeAssign",
	))

	server := &http.Server{
		Addr:              ":" + port,
		Handler:           mux,
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      15 * time.Second,
		IdleTimeout:       60 * time.Second,
	}

	errCh := make(chan error, 1)
	go func() {
		logger.Info("dispatch optimizer service listening", "addr", server.Addr)
		if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			errCh <- err
		}
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)

	exitCode := 0
	select {
	case sig := <-quit:
		logger.Info("received signal, shutting down gracefully", "signal", sig.String())
	case err := <-errCh:
		logger.Error("server failed", "error", err)
		exitCode = 1
	}

	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	if err := server.Shutdown(ctx); err != nil {
		logger.Error("server shutdown failed", "error", err)
		exitCode = 1
	}
	if shutdownTracer != nil {
		if err := shutdownTracer(ctx); err != nil {
			logger.Error("tracer shutdown failed", "error", err)
			exitCode = 1
		}
	}
	logger.Info("server stopped")
	if exitCode != 0 {
		os.Exit(exitCode)
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

func handleAssign(w http.ResponseWriter, r *http.Request, logger *slog.Logger, metrics *serviceMetrics) {
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
		logger.Warn("invalid request body", "error", err)
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	if err := decoder.Decode(&struct{}{}); err != io.EOF {
		logger.Warn("invalid request body", "error", err)
		writeError(w, http.StatusBadRequest, "request body must contain a single JSON object")
		return
	}
	if err := validateRequest(req); err != nil {
		logger.Warn("invalid request", "error", err)
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}

	constraints := buildConstraints(req, logger)
	tracer := otel.Tracer(serviceName)
	_, span := tracer.Start(r.Context(), "optimize.assign")
	span.SetAttributes(
		attribute.Int("riders", len(req.Riders)),
		attribute.Int("orders", len(req.Orders)),
		attribute.Int("capacity", req.Capacity),
		attribute.StringSlice("constraints", constraints.Names()),
	)
	start := time.Now()
	assignments, unassigned := optimizeAssignments(req.Riders, req.Orders, constraints)
	metrics.optimizeDuration.Observe(time.Since(start).Seconds())
	assignedCount := 0
	totalDistance := 0.0
	for _, assignment := range assignments {
		assignedCount += len(assignment.OrderIDs)
		totalDistance += assignment.TotalDistance
	}
	metrics.assignedOrders.Add(float64(assignedCount))
	metrics.unassignedOrders.Add(float64(len(unassigned)))
	span.SetAttributes(
		attribute.Int("assigned_orders", assignedCount),
		attribute.Int("unassigned_orders", len(unassigned)),
		attribute.Float64("total_distance_km", totalDistance),
	)
	span.End()
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
		if err := validatePosition(rider.Position); err != nil {
			return fmt.Errorf("rider %s: %w", rider.ID, err)
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
		if err := validatePosition(order.Position); err != nil {
			return fmt.Errorf("order %s: %w", order.ID, err)
		}
		orderIDs[order.ID] = struct{}{}
	}

	return nil
}

func validatePosition(p Position) error {
	if p.Lat < -90 || p.Lat > 90 {
		return errors.New("lat must be between -90 and 90")
	}
	if p.Lng < -180 || p.Lng > 180 {
		return errors.New("lng must be between -180 and 180")
	}
	return nil
}

func buildConstraints(req AssignRequest, logger *slog.Logger) ConstraintSet {
	selection := strings.TrimSpace(os.Getenv("DISPATCH_CONSTRAINTS"))
	if selection == "" {
		selection = "zone"
	}
	parts := strings.Split(selection, ",")
	constraints := make(ConstraintSet, 0, len(parts)+1)
	seen := map[string]bool{"capacity": true}
	constraints = append(constraints, constraintFactories["capacity"](req))
	for _, part := range parts {
		name := strings.ToLower(strings.TrimSpace(part))
		if name == "" || seen[name] {
			continue
		}
		factory, ok := constraintFactories[name]
		if !ok {
			logger.Warn("unknown constraint", "constraint", name)
			continue
		}
		constraints = append(constraints, factory(req))
		seen[name] = true
	}
	return constraints
}

func newMetrics() *serviceMetrics {
	metrics := &serviceMetrics{
		requestCount: prometheus.NewCounterVec(
			prometheus.CounterOpts{
				Name: "dispatch_optimizer_http_requests_total",
				Help: "Total number of HTTP requests handled.",
			},
			[]string{"path", "method", "status"},
		),
		requestDuration: prometheus.NewHistogramVec(
			prometheus.HistogramOpts{
				Name:    "dispatch_optimizer_http_request_duration_seconds",
				Help:    "HTTP request duration in seconds.",
				Buckets: prometheus.DefBuckets,
			},
			[]string{"path", "method", "status"},
		),
		optimizeDuration: prometheus.NewHistogram(
			prometheus.HistogramOpts{
				Name:    "dispatch_optimizer_assignment_duration_seconds",
				Help:    "Assignment optimization duration in seconds.",
				Buckets: prometheus.DefBuckets,
			},
		),
		assignedOrders: prometheus.NewCounter(
			prometheus.CounterOpts{
				Name: "dispatch_optimizer_assigned_orders_total",
				Help: "Total number of assigned orders.",
			},
		),
		unassignedOrders: prometheus.NewCounter(
			prometheus.CounterOpts{
				Name: "dispatch_optimizer_unassigned_orders_total",
				Help: "Total number of unassigned orders.",
			},
		),
	}
	prometheus.MustRegister(
		metrics.requestCount,
		metrics.requestDuration,
		metrics.optimizeDuration,
		metrics.assignedOrders,
		metrics.unassignedOrders,
	)
	return metrics
}

func initLogger() *slog.Logger {
	level := slog.LevelInfo
	if levelText := strings.TrimSpace(os.Getenv("LOG_LEVEL")); levelText != "" {
		var parsed slog.Level
		if err := parsed.UnmarshalText([]byte(levelText)); err == nil {
			level = parsed
		}
	}
	handler := slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: level})
	return slog.New(handler).With("service", serviceName)
}

func initTracerProvider(ctx context.Context, logger *slog.Logger) func(context.Context) error {
	res, err := resource.New(ctx, resource.WithAttributes(semconv.ServiceName(serviceName)))
	if err != nil {
		logger.Error("failed to create otel resource", "error", err)
		res = resource.Default()
	}
	exporter, err := otlptracegrpc.New(ctx)
	if err != nil {
		logger.Error("failed to create otlp exporter", "error", err)
		tp := sdktrace.NewTracerProvider(sdktrace.WithResource(res))
		otel.SetTracerProvider(tp)
		otel.SetTextMapPropagator(propagation.NewCompositeTextMapPropagator(
			propagation.TraceContext{},
			propagation.Baggage{},
		))
		return tp.Shutdown
	}
	tp := sdktrace.NewTracerProvider(
		sdktrace.WithBatcher(exporter),
		sdktrace.WithResource(res),
	)
	otel.SetTracerProvider(tp)
	otel.SetTextMapPropagator(propagation.NewCompositeTextMapPropagator(
		propagation.TraceContext{},
		propagation.Baggage{},
	))
	return tp.Shutdown
}

func instrumentHandler(route string, logger *slog.Logger, metrics *serviceMetrics, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		recorder := &responseRecorder{ResponseWriter: w}
		next.ServeHTTP(recorder, r)
		status := recorder.status
		if status == 0 {
			status = http.StatusOK
		}
		duration := time.Since(start)
		metrics.requestCount.WithLabelValues(route, r.Method, strconv.Itoa(status)).Inc()
		metrics.requestDuration.WithLabelValues(route, r.Method, strconv.Itoa(status)).Observe(duration.Seconds())
		if route != "health" {
			traceID, spanID := traceFields(r.Context())
			logger.Info("request completed",
				"method", r.Method,
				"path", r.URL.Path,
				"status", status,
				"duration_ms", duration.Milliseconds(),
				"bytes", recorder.bytes,
				"trace_id", traceID,
				"span_id", spanID,
			)
		}
	})
}

func traceFields(ctx context.Context) (string, string) {
	spanCtx := oteltrace.SpanContextFromContext(ctx)
	if !spanCtx.IsValid() {
		return "", ""
	}
	return spanCtx.TraceID().String(), spanCtx.SpanID().String()
}

func optimizeAssignments(riders []Rider, orders []Order, constraints ConstraintSet) ([]Assignment, []string) {
	assigned := make(map[string]bool, len(orders))
	assignments := make([]Assignment, 0, len(riders))

	for _, rider := range riders {
		current := rider.Position
		orderIDs := make([]string, 0)
		assignedOrders := make([]Order, 0)
		totalDistance := 0.0

		for {
			nearestIndex := -1
			nearestDistance := math.MaxFloat64
			nearestID := ""

			for i, order := range orders {
				if assigned[order.ID] {
					continue
				}
				if !constraints.Allows(rider, order, assignedOrders) {
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

			selected := orders[nearestIndex]
			orderIDs = append(orderIDs, selected.ID)
			assignedOrders = append(assignedOrders, selected)
			totalDistance += nearestDistance
			current = selected.Position
			assigned[selected.ID] = true
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

// haversine distance in kilometres
func distance(a, b Position) float64 {
	const earthRadiusKm = 6371.0
	dLat := (b.Lat - a.Lat) * math.Pi / 180.0
	dLng := (b.Lng - a.Lng) * math.Pi / 180.0
	lat1 := a.Lat * math.Pi / 180.0
	lat2 := b.Lat * math.Pi / 180.0
	sinDLat := math.Sin(dLat / 2)
	sinDLng := math.Sin(dLng / 2)
	h := sinDLat*sinDLat + math.Cos(lat1)*math.Cos(lat2)*sinDLng*sinDLng
	return 2 * earthRadiusKm * math.Asin(math.Sqrt(h))
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
