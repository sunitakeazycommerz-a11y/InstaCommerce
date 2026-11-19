package optimizer

import (
	"context"
	"math"
	"sort"
	"time"
)

type riderBagLoad struct {
	count        int
	weightKg     float64
	volumeLiters float64
}

func (l riderBagLoad) canAdd(rider RiderState, order OrderRequest, maxPerRider int) bool {
	if rider.ActiveOrders+l.count+1 > maxPerRider {
		return false
	}
	maxW, maxV := effectiveBagLimits(rider)
	if order.Weight > 0 && rider.CurrentBagWeightKg+l.weightKg+order.Weight > maxW {
		return false
	}
	if order.VolumeLiters > 0 && rider.CurrentBagVolumeLiters+l.volumeLiters+order.VolumeLiters > maxV {
		return false
	}
	return true
}

func (s *Solver) buildCostMatrix(riders []RiderState, orders []OrderRequest) ([][]float64, [][]float64) {
	now := time.Now()
	costMatrix := make([][]float64, len(riders))
	priorityCost := make([][]float64, len(riders))
	for i, rider := range riders {
		costMatrix[i] = make([]float64, len(orders))
		priorityCost[i] = make([]float64, len(orders))
		speed := rider.AvgSpeedKmh
		if speed <= 0 {
			speed = 20.0
		}
		for j, order := range orders {
			dist := HaversineDistance(rider.Position, order.Position)
			etaMin := EstimateETAMinutes(dist, speed, DefaultTrafficFactor)
			// Busy riders carry a proportional delay weight; idle riders pay 0.
			idlePenalty := float64(rider.ActiveOrders) * etaMin * 0.5
			slaRisk := s.computeSLARisk(order, etaMin, now)
			baseCost := s.cfg.WeightDeliveryTime*etaMin +
				s.cfg.WeightIdleTime*idlePenalty +
				s.cfg.WeightSLABreach*slaRisk*100
			costMatrix[i][j] = baseCost
			priorityCost[i][j] = baseCost - s.orderUrgencyBoost(order, slaRisk)
		}
	}
	return costMatrix, priorityCost
}

func (s *Solver) orderUrgencyBoost(order OrderRequest, slaRisk float64) float64 {
	boost := slaRisk * 10.0
	if order.IsExpressOrder {
		boost += 8.0
	}
	if order.isColdChain() {
		if order.isFrozen() {
			boost += s.cfg.ColdChainPriorityBoost * 1.5
		} else {
			boost += s.cfg.ColdChainPriorityBoost
		}
	}
	return boost
}

func (s *Solver) buildFeasibilityMask(riders []RiderState, orders []OrderRequest) [][]bool {
	mask := make([][]bool, len(riders))
	for i, rider := range riders {
		mask[i] = make([]bool, len(orders))
		for j, order := range orders {
			ok, _ := s.checker.CheckAll(rider, order)
			mask[i][j] = ok
		}
	}
	return mask
}

func (s *Solver) hungarianAssign(
	ctx context.Context,
	riders []RiderState,
	orders []OrderRequest,
	priorityCost [][]float64,
	feasible [][]bool,
	batches [][]int,
) map[int][]int {
	nR, nO := len(riders), len(orders)
	assignments := make(map[int][]int, nR)
	orderAssigned := make([]bool, nO)
	loads := make([]riderBagLoad, nR)

	orderBatch := make(map[int]int, nO)
	for bi, batch := range batches {
		for _, oi := range batch {
			orderBatch[oi] = bi
		}
	}

	type edge struct {
		rider, order int
		score        float64
	}
	edges := make([]edge, 0, nR*nO)
	for i := 0; i < nR; i++ {
		for j := 0; j < nO; j++ {
			if feasible[i][j] {
				edges = append(edges, edge{rider: i, order: j, score: priorityCost[i][j]})
			}
		}
	}
	sort.Slice(edges, func(a, b int) bool {
		if edges[a].score != edges[b].score {
			return edges[a].score < edges[b].score
		}
		if edges[a].order != edges[b].order {
			return edges[a].order < edges[b].order
		}
		return edges[a].rider < edges[b].rider
	})

	maxPerRider := s.effectiveMaxPerRider()
	for _, e := range edges {
		if ctx.Err() != nil {
			break
		}
		if orderAssigned[e.order] || !loads[e.rider].canAdd(riders[e.rider], orders[e.order], maxPerRider) {
			continue
		}

		if bi, inBatch := orderBatch[e.order]; inBatch && len(batches[bi]) > 1 {
			if s.tryAssignBatch(riders[e.rider], orders, batches[bi], &loads[e.rider], feasible[e.rider], orderAssigned, assignments, e.rider, maxPerRider) {
				continue
			}
		}

		assignments[e.rider] = append(assignments[e.rider], e.order)
		orderAssigned[e.order] = true
		loads[e.rider].count++
		loads[e.rider].weightKg += orders[e.order].Weight
		loads[e.rider].volumeLiters += orders[e.order].VolumeLiters
	}

	return assignments
}

func (s *Solver) tryAssignBatch(
	rider RiderState,
	orders []OrderRequest,
	batch []int,
	load *riderBagLoad,
	riderFeasible []bool,
	orderAssigned []bool,
	assignments map[int][]int,
	riderIdx int,
	maxPerRider int,
) bool {
	sim := *load
	var candidates []int
	for _, oi := range batch {
		if orderAssigned[oi] {
			continue
		}
		if !riderFeasible[oi] || !sim.canAdd(rider, orders[oi], maxPerRider) {
			return false
		}
		sim.count++
		sim.weightKg += orders[oi].Weight
		sim.volumeLiters += orders[oi].VolumeLiters
		candidates = append(candidates, oi)
	}
	if len(candidates) == 0 {
		return false
	}
	for _, oi := range candidates {
		assignments[riderIdx] = append(assignments[riderIdx], oi)
		orderAssigned[oi] = true
	}
	*load = sim
	return true
}

func (s *Solver) postOptimise(
	ctx context.Context,
	riders []RiderState,
	orders []OrderRequest,
	assignments map[int][]int,
	cost [][]float64,
	feasible [][]bool,
) map[int][]int {
	maxIter := s.cfg.MaxIterations
	if maxIter <= 0 {
		maxIter = DefaultMaxIterations
	}
	maxPerRider := s.effectiveMaxPerRider()

	loads := make([]riderBagLoad, len(riders))
	for ri, orderIdxs := range assignments {
		for _, oi := range orderIdxs {
			loads[ri].count++
			loads[ri].weightKg += orders[oi].Weight
			loads[ri].volumeLiters += orders[oi].VolumeLiters
		}
	}

	for iter := 0; iter < maxIter; iter++ {
		if ctx.Err() != nil {
			break
		}
		improved := false
		riderIdxs := make([]int, 0, len(assignments))
		for ri := range assignments {
			riderIdxs = append(riderIdxs, ri)
		}
		sort.Ints(riderIdxs)

		for _, ra := range riderIdxs {
			for rb := 0; rb < len(riders); rb++ {
				if ra == rb {
					continue
				}
				for oa := 0; oa < len(assignments[ra]); oa++ {
					oi := assignments[ra][oa]
					if !feasible[rb][oi] || !loads[rb].canAdd(riders[rb], orders[oi], maxPerRider) {
						continue
					}
					if cost[rb][oi] < cost[ra][oi] {
						assignments[rb] = append(assignments[rb], oi)
						assignments[ra] = append(assignments[ra][:oa], assignments[ra][oa+1:]...)
						loads[rb].count++
						loads[rb].weightKg += orders[oi].Weight
						loads[rb].volumeLiters += orders[oi].VolumeLiters
						loads[ra].count--
						loads[ra].weightKg -= orders[oi].Weight
						loads[ra].volumeLiters -= orders[oi].VolumeLiters
						improved = true
						break
					}
				}
				if improved {
					break
				}
			}
			if improved {
				break
			}
		}
		if !improved {
			break
		}
	}
	return assignments
}

func (s *Solver) effectiveMaxPerRider() int {
	if s.cfg.MaxOrdersPerRider <= 0 {
		return DefaultMaxOrdersPerRider
	}
	return s.cfg.MaxOrdersPerRider
}

func (s *Solver) buildResult(
	riders []RiderState,
	orders []OrderRequest,
	assignments map[int][]int,
	start time.Time,
) *SolverResult {
	now := time.Now()
	result := &SolverResult{}
	assignedSet := make(map[int]bool, len(orders))

	var totalDist, totalOrderETA float64
	var totalAssigned int

	for ri, orderIdxs := range assignments {
		if len(orderIdxs) == 0 {
			continue
		}
		rider := riders[ri]
		speed := rider.AvgSpeedKmh
		if speed <= 0 {
			speed = 20.0
		}
		var dist, cumETA, maxRisk float64
		orderIDs := make([]string, 0, len(orderIdxs))
		waypoints := []Position{rider.Position}
		current := rider.Position

		for _, oi := range orderIdxs {
			order := orders[oi]
			d := HaversineDistance(current, order.Position)
			dist += d
			cumETA += EstimateETAMinutes(d, speed, DefaultTrafficFactor)
			totalOrderETA += cumETA
			if risk := s.computeSLARisk(order, cumETA, now); risk > maxRisk {
				maxRisk = risk
			}
			orderIDs = append(orderIDs, order.ID)
			waypoints = append(waypoints, order.Position)
			current = order.Position
			assignedSet[oi] = true
		}

		result.Assignments = append(result.Assignments, OptimalAssignment{
			RiderID:             rider.ID,
			OrderIDs:            orderIDs,
			EstimatedETAMinutes: cumETA,
			TotalDistanceKm:     dist,
			SLABreachRisk:       maxRisk,
			RouteWaypoints:      waypoints,
		})
		totalDist += dist
		totalAssigned += len(orderIDs)
		if maxRisk > 0.5 {
			result.Metrics.SLABreachCount++
		}
		if len(orderIDs) > 1 {
			result.Metrics.BatchedOrders += len(orderIDs)
		}
	}

	sort.Slice(result.Assignments, func(i, j int) bool {
		return result.Assignments[i].RiderID < result.Assignments[j].RiderID
	})
	for oi, order := range orders {
		if !assignedSet[oi] {
			result.UnassignedOrders = append(result.UnassignedOrders, order.ID)
		}
	}
	sort.Strings(result.UnassignedOrders)

	result.Metrics.TotalDistance = totalDist
	if totalAssigned > 0 {
		result.Metrics.AvgDeliveryTime = totalOrderETA / float64(totalAssigned)
	}
	result.TotalCost = s.cfg.WeightDeliveryTime*totalOrderETA +
		s.cfg.WeightSLABreach*float64(result.Metrics.SLABreachCount)*100
	result.SolveDurationMs = time.Since(start).Milliseconds()
	return result
}

func (s *Solver) computeSLARisk(order OrderRequest, etaMin float64, now time.Time) float64 {
	var remaining float64
	if !order.SLADeadline.IsZero() {
		remaining = order.SLADeadline.Sub(now).Minutes()
	} else if !order.CreatedAt.IsZero() {
		remaining = order.CreatedAt.Add(10 * time.Minute).Sub(now).Minutes()
	} else {
		remaining = 10.0
	}
	if order.isFrozen() {
		remaining -= 5.0
	} else if order.isColdChain() {
		remaining -= 3.0
	}
	if order.IsExpressOrder {
		remaining -= 2.0
	}
	if remaining <= 0 {
		return 1.0
	}
	ratio := etaMin / remaining
	risk := 1.0 / (1.0 + math.Exp(-10*(ratio-0.7)))
	return clamp(risk, 0.0, 1.0)
}
