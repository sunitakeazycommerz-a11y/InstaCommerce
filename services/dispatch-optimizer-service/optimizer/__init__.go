// Package optimizer provides multi-objective dispatch optimization for
// Q-commerce rider-to-order assignment. It minimises a weighted combination of
// total delivery time, rider idle time, and SLA-breach probability while
// honouring hard operational constraints (capacity, zone, battery/fuel,
// consecutive-delivery limits, new-rider distance caps).
//
// The solver is safe for concurrent use once constructed via [NewSolver].
package optimizer
