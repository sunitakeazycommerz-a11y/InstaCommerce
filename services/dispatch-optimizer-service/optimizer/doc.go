// Package optimizer provides multi-objective dispatch optimization for
// Q-commerce rider-to-order assignment. It minimises a weighted combination of
// total delivery time, rider idle load, and SLA-breach probability while
// honouring hard operational constraints (capacity, bag weight/volume,
// cold-chain thermal suitability, zone, battery, consecutive-delivery limits,
// and new-rider distance caps).
package optimizer
