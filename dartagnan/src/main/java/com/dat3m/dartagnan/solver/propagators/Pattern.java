package com.dat3m.dartagnan.solver.propagators;

import com.dat3m.dartagnan.program.event.Event;
import com.dat3m.dartagnan.utils.collections.IndexedDomain;
import com.dat3m.dartagnan.utils.collections.IndexedSet;
import com.dat3m.dartagnan.utils.collections.SetUtil;

import java.util.*;

import static java.util.Collections.emptyList;

public abstract class Pattern {

    private final IndexedDomain<Event> domain;

    final List<Node> nodes = new ArrayList<>();
    final List<Edge> edges = new ArrayList<>();

    // ------------------------------------------------------------------------------------------
    // Construction

    public Pattern(final IndexedDomain<Event> domain) {
        this.domain = domain;
    }

    public Node addPatternNode() {
        return addPatternNode(emptyList());
    }

    public Node addPatternNode(final List<IndexedSet<Event>> conditions) {
        final Node node = new Node(nodes.size(), intersect(conditions));
        nodes.add(node);
        return node;
    }

    private IndexedSet<Event> intersect(final List<IndexedSet<Event>> conditions) {
        return switch (conditions.size()) {
            case 0 -> new IndexedSet<>(domain.fullSet());
            case 1 -> conditions.get(0);
            case 2 -> (IndexedSet<Event>) SetUtil.intersection(conditions.get(0), conditions.get(1));
            default -> {
                final IndexedSet<Event> intersection = new IndexedSet<>(conditions.get(0));
                for (final IndexedSet<?> condition : conditions.subList(1, conditions.size())) {
                    for (int index : intersection.toIndexArray()) {
                        if (!condition.test(index)) {
                            intersection.exchange(index, false);
                        }
                    }
                }
                yield intersection;
            }
        };
    }

    public Edge addPatternEdge(final VarGraph graph, final Node from, final Node to) {
        final Edge edge = new Edge(graph, from, to, edges.size());
        edges.add(edge);
        return edge;
    }

    public Edge appendPatternEdge(final VarGraph graph, final boolean isInverse, final List<IndexedSet<Event>> conditions) {
        final Node lastNode = nodes.isEmpty() ? addPatternNode() : nodes.get(nodes.size() - 1);
        lastNode.addCondition(intersect(conditions));
        final Node newNode = addPatternNode();
        return addPatternEdge(graph, isInverse ? newNode : lastNode, isInverse ? lastNode : newNode);
    }

    public Edge appendLastPatternEdge(final VarGraph graph, final boolean isInverse, final List<IndexedSet<Event>> conditions) {
        final Node firstNode;
        final Node lastNode;
        if (nodes.isEmpty()) {
            firstNode = addPatternNode();
            lastNode = firstNode;
        } else {
            firstNode = nodes.get(0);
            lastNode = nodes.get(nodes.size() - 1);
        }
        lastNode.addCondition(intersect(conditions));
        return addPatternEdge(graph, isInverse ? firstNode : lastNode, isInverse ? lastNode : firstNode);
    }

    public Edge closeBuild(final List<IndexedSet<Event>> conditions) {
        final Edge lastEdge = edges.remove(edges.size() - 1);
        final Node lastNode = nodes.remove(nodes.size() - 1);
        final Node firstNode = nodes.get(0);
        firstNode.addCondition(intersect(conditions));
        final Node lastEdgeFrom = lastEdge.from;
        final Node lastEdgeTo = lastEdge.to;
        final Edge newLastEdge = new Edge(lastEdge.graph, lastEdgeFrom == lastNode ? firstNode : lastEdgeFrom,
                lastEdgeTo == lastNode ? firstNode : lastEdgeTo, lastEdge.id);
        edges.add(newLastEdge);
        return lastEdge;
    }

    public abstract void build();

    // ------------------------------------------------------------------------------------------
    // Matching

    public record MatchingResult(List<int[]> matches, List<int[]> theoryPropagations) {
    }

    public abstract MatchingResult addEdge(final Edge edge, int from, int to);

    public abstract void removeEdge(final Edge edge, final int from, final int to);

    public abstract void push();

    public abstract void pop(final int numLevels);

    // ===============================================================================================
    // ================================ Internal classes =============================================
    // ===============================================================================================

    public record Node(int id, IndexedSet<?> condition) {
        @Override
        public String toString() { return "n#" + id; }

        @Override
        public boolean equals(Object obj) { return this == obj; }

        void addCondition(final IndexedSet<?> newCondition) {
            for (int index : condition.toIndexArray()) {
                if (!newCondition.test(index)) {
                    condition.exchange(index, false);
                }
            }
        }

        public boolean check(final int event) {
            return condition.test(event);
        }
    }

    public record Edge(VarGraph graph, Node from, Node to, int id) {
        @Override
        public String toString() {
            return String.format("%s-%s->%s", from, graph.getName(), to);
        }
        @Override
        public boolean equals(final Object obj) { return this == obj; }
    }
}
