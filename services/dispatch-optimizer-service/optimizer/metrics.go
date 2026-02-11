package optimizer

import "github.com/prometheus/client_golang/prometheus"

// Prometheus metrics for the multi-objective solver. All metrics are registered
// at package init time so they are available as soon as the package is imported.
var (
	// SolveDuration tracks the wall-clock time taken by each Solve call.
	SolveDuration = prometheus.NewHistogram(prometheus.HistogramOpts{
		Namespace: "dispatch_optimizer",
		Subsystem: "solver",
		Name:      "solve_duration_seconds",
		Help:      "Duration of the multi-objective solver in seconds.",
		Buckets:   []float64{0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5},
	})

	// AssignedOrders counts orders successfully assigned by the solver.
	AssignedOrders = prometheus.NewCounter(prometheus.CounterOpts{
		Namespace: "dispatch_optimizer",
		Subsystem: "solver",
		Name:      "assigned_orders_total",
		Help:      "Total number of orders assigned by the multi-objective solver.",
	})

	// UnassignedOrders counts orders the solver could not assign.
	UnassignedOrders = prometheus.NewCounter(prometheus.CounterOpts{
		Namespace: "dispatch_optimizer",
		Subsystem: "solver",
		Name:      "unassigned_orders_total",
		Help:      "Total number of orders left unassigned by the multi-objective solver.",
	})

	// BatchedOrders counts orders that were batched together in a single trip.
	BatchedOrders = prometheus.NewCounter(prometheus.CounterOpts{
		Namespace: "dispatch_optimizer",
		Subsystem: "solver",
		Name:      "batched_orders_total",
		Help:      "Total number of orders batched together for delivery.",
	})

	// SLABreachRisk records the computed breach-risk score per assignment.
	SLABreachRisk = prometheus.NewHistogram(prometheus.HistogramOpts{
		Namespace: "dispatch_optimizer",
		Subsystem: "solver",
		Name:      "sla_breach_risk",
		Help:      "Distribution of SLA breach risk scores (0.0–1.0) across assignments.",
		Buckets:   []float64{0.05, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0},
	})

	// AvgDeliveryDistance records the total distance of each assignment.
	AvgDeliveryDistance = prometheus.NewHistogram(prometheus.HistogramOpts{
		Namespace: "dispatch_optimizer",
		Subsystem: "solver",
		Name:      "delivery_distance_km",
		Help:      "Distribution of total delivery distances in kilometres per assignment.",
		Buckets:   []float64{0.5, 1, 2, 3, 5, 8, 10, 15, 20},
	})
)

func init() {
	prometheus.MustRegister(
		SolveDuration,
		AssignedOrders,
		UnassignedOrders,
		BatchedOrders,
		SLABreachRisk,
		AvgDeliveryDistance,
	)
}
