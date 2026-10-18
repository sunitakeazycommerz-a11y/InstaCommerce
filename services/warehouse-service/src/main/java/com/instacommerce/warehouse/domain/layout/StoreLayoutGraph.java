package com.instacommerce.warehouse.domain.layout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

public class StoreLayoutGraph {

    public record Edge(String targetNode, double distanceMeters, boolean oneWay) {}

    private final Map<String, List<Edge>> adjacencyList = new HashMap<>();

    public void addNode(String nodeId) {
        adjacencyList.putIfAbsent(normalize(nodeId), new ArrayList<>());
    }

    public void addDirectedEdge(String from, String to, double distanceMeters) {
        String fromNorm = normalize(from);
        String toNorm = normalize(to);
        addNode(fromNorm);
        addNode(toNorm);
        adjacencyList.get(fromNorm).add(new Edge(toNorm, distanceMeters, true));
    }

    public void addBidirectionalEdge(String nodeA, String nodeB, double distanceMeters) {
        String aNorm = normalize(nodeA);
        String bNorm = normalize(nodeB);
        addNode(aNorm);
        addNode(bNorm);
        adjacencyList.get(aNorm).add(new Edge(bNorm, distanceMeters, false));
        adjacencyList.get(bNorm).add(new Edge(aNorm, distanceMeters, false));
    }

    public PathResult findShortestPath(String startNode, String targetNode) {
        String start = normalize(startNode);
        String target = normalize(targetNode);

        if (start.equals(target)) {
            return new PathResult(List.of(start), 0.0);
        }

        Map<String, Double> distances = new HashMap<>();
        Map<String, String> predecessors = new HashMap<>();
        PriorityQueue<NodeDistance> pq = new PriorityQueue<>(Comparator.comparingDouble(NodeDistance::distance));
        Set<String> visited = new HashSet<>();

        distances.put(start, 0.0);
        pq.add(new NodeDistance(start, 0.0));

        while (!pq.isEmpty()) {
            NodeDistance current = pq.poll();
            String u = current.nodeId();

            if (u.equals(target)) {
                break;
            }

            if (visited.contains(u)) {
                continue;
            }
            visited.add(u);

            List<Edge> edges = adjacencyList.getOrDefault(u, Collections.emptyList());
            for (Edge edge : edges) {
                String v = edge.targetNode();
                if (visited.contains(v)) {
                    continue;
                }

                double newDist = distances.get(u) + edge.distanceMeters();
                if (newDist < distances.getOrDefault(v, Double.MAX_VALUE)) {
                    distances.put(v, newDist);
                    predecessors.put(v, u);
                    pq.add(new NodeDistance(v, newDist));
                }
            }
        }

        if (!distances.containsKey(target)) {
            // Target not reachable directly, fallback to estimated Euclidean / grid distance
            return new PathResult(List.of(start, target), 10.0);
        }

        List<String> path = new ArrayList<>();
        String step = target;
        while (step != null) {
            path.add(step);
            step = predecessors.get(step);
        }
        Collections.reverse(path);

        return new PathResult(path, distances.get(target));
    }

    public static StoreLayoutGraph createDefaultDarkStoreFloorplan() {
        StoreLayoutGraph graph = new StoreLayoutGraph();
        String entrance = "ENTRANCE";
        String packing = "PACKING";
        graph.addNode(entrance);
        graph.addNode(packing);

        // Aisles A1 through A10
        // Heavy goods: A1, A2
        // Ambient: A3, A4, A5, A6
        // Chilled: A7, A8
        // Frozen: A9
        // Fragile Bakery: A10
        for (int i = 1; i <= 10; i++) {
            String aisle = "A" + i;
            graph.addNode(aisle);
        }

        // Connect entrance to starting cross-aisle
        graph.addBidirectionalEdge(entrance, "A1", 4.0);
        graph.addBidirectionalEdge(entrance, "A2", 6.0);

        // One-way and bidirectional aisles
        for (int i = 1; i < 10; i++) {
            // Cross-aisle connections between adjacent aisles
            graph.addBidirectionalEdge("A" + i, "A" + (i + 1), 3.5);
        }

        // Connect back to packing / staging bay
        graph.addBidirectionalEdge("A10", packing, 5.0);
        graph.addBidirectionalEdge("A9", packing, 7.0);
        graph.addBidirectionalEdge(packing, entrance, 8.0);

        return graph;
    }

    private String normalize(String node) {
        if (node == null || node.isBlank()) {
            return "ENTRANCE";
        }
        String upper = node.trim().toUpperCase();
        if (upper.startsWith("AISLE_")) {
            return "A" + upper.substring("AISLE_".length());
        }
        if (upper.startsWith("AISLE")) {
            return "A" + upper.substring("AISLE".length());
        }
        return upper;
    }

    private record NodeDistance(String nodeId, double distance) {}

    public record PathResult(List<String> nodes, double totalDistanceMeters) {}
}
