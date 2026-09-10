package com.dat3m.dartagnan.solver.caat4wmm;

import com.dat3m.dartagnan.solver.caat.predicates.CAATPredicate;
import com.dat3m.dartagnan.solver.caat.predicates.relationGraphs.RelationGraph;
import com.dat3m.dartagnan.wmm.Definition;
import com.dat3m.dartagnan.wmm.Relation;
import com.dat3m.dartagnan.wmm.definition.Composition;

import java.util.*;

public class ViolationPattern {

    private final List<Node> nodes = new ArrayList<>();
    private final List<Edge> edges = new ArrayList<>();

    private boolean isIrreflexive = true;

    // ------------------------------------------------------------------------------------------
    // Construction

    public static ViolationPattern ofRelation(final Relation relation, final Map<Relation, CAATPredicate> relationGraphMap, boolean isIrreflexive) {
        final ViolationPattern pattern = new ViolationPattern();
        if (relation.getDefinition() instanceof final Composition composition) {
            final List<Definition> components = composition.getComponents();
            final Map<Relation, List<Edge>> patternEdges = new LinkedHashMap<>();
            int i = 0;
            for (final Definition definition : components) {
                final Relation compRel = definition.getDefinedRelation();
                final RelationGraph graph = (RelationGraph) relationGraphMap.get(compRel);
                final Edge patternEdge = isIrreflexive && i++ == components.size() - 1 ? pattern.appendLastPatternEdge(graph) : pattern.appendPatternEdge(graph);
                patternEdges.computeIfAbsent(compRel, k -> new ArrayList<>()).add(patternEdge);
            }
            if (!isIrreflexive) {
                pattern.appendLastPatternEdge(null);
            }
        } else {
            pattern.appendPatternEdge((RelationGraph) relationGraphMap.get(relation));
        }
        return pattern;
    }

    public Node addNode() {
        final Node node = new Node(nodes.size());
        nodes.add(node);
        return node;
    }

    public Edge addEdge(final RelationGraph graph, final Node from, final Node to) {
        final Edge edge = new Edge(graph, from, to, edges.size(), false);
        edges.add(edge);
        return edge;
    }

    public Edge appendPatternEdge(final RelationGraph graph) {
        final Node lastNode = nodes.isEmpty() ? addNode() : nodes.get(nodes.size() - 1);
        final Node newNode = addNode();
        return addEdge(graph, lastNode, newNode);
    }

    public Edge appendLastPatternEdge(final RelationGraph graph) {
        final Node firstNode;
        final Node lastNode;
        if (nodes.isEmpty()) {
            firstNode = addNode();
            lastNode = firstNode;
        } else {
            firstNode = nodes.get(0);
            lastNode = nodes.get(nodes.size() - 1);
        }
        if (graph == null) {
            isIrreflexive = false;
            return null;
        }
        return addEdge(graph, lastNode, firstNode);
    }

    public List<Node> getNodes() {
        return nodes;
    }

    public List<Edge> getEdges() {
        return edges;
    }

    // ------------------------------------------------------------------------------------------
    // Matching

    public Collection<Match> findMatches(final int from, final int to) {
        final Node firstNode = nodes.get(0);
        if (isIrreflexive) {
            return findMatches(firstNode, to, null, from);
        } else {
            final int numOfNodes = nodes.size();
            return findMatches(firstNode, to, nodes.get(numOfNodes - 1), from);
        }
    }

    @SuppressWarnings("ForLoopReplaceableByForEach")
    public Collection<Match> findMatches(final Node from, int fromId, final Node to, int toId) {
        final NodeSet visited = new NodeSet();
        visited.add(from);
        if (to != null) {
            visited.add(to);
        }

        final Match firstMatch = new Match(nodes.size());
        firstMatch.setMatch(from, fromId);
        if (to != null) {
            firstMatch.setMatch(to, toId);
        }

        List<Match> curMatches = new ArrayList<>();
        curMatches.add(firstMatch);

        final EdgeQueue queue = new EdgeQueue(this);

        Edge nextEdgeToJoin;
        while ((nextEdgeToJoin = queue.pop(visited)) != null) {
            final boolean joinAtSource = visited.add(nextEdgeToJoin.to);
            final boolean joinAtTarget = visited.add(nextEdgeToJoin.from);
            final boolean doPrune = !(joinAtSource || joinAtTarget);

            if (doPrune) {
                curMatches = prune(curMatches, nextEdgeToJoin);
            } else {
                final Node matchedNode = joinAtSource ? nextEdgeToJoin.from : nextEdgeToJoin.to;
                final Node unmatchedNode = joinAtSource ? nextEdgeToJoin.to : nextEdgeToJoin.from;
                final List<Match> nextMatches = new ArrayList<>();
                for (int i = 0, matchSize = curMatches.size(); i < matchSize; ++i) {
                    final Match match = curMatches.get(i);
                    for (final int nodeId : joinAlong(match, matchedNode, nextEdgeToJoin)) {
                        nextMatches.add(match.with(unmatchedNode, nodeId));
                    }
                }
                curMatches = nextMatches;
            }

            if (curMatches.isEmpty()) {
                return curMatches;
            }
        }


        return curMatches;
    }

    @SuppressWarnings("Convert2MethodRef")
    private int[] joinAlong(final Match partialMatch, final Node node, final Edge edge) {
        final int nodeMatch = partialMatch.atNode(node);
        final RelationGraph relationGraph = edge.graph;
        return (edge.from == node ? relationGraph.outEdgeStream(nodeMatch).mapToInt(joinEdge -> joinEdge.getSecond())
                : relationGraph.inEdgeStream(nodeMatch).mapToInt(joinEdge -> joinEdge.getFirst()))
                .toArray();
    }

    private List<Match> prune(final List<Match> matches, final Edge edge) {
        final RelationGraph relationGraph = edge.graph;
        final List<Match> remainingMatches = new ArrayList<>();
        for (final Match match : matches) {
            final int source = match.atNode(edge.from);
            final int target = match.atNode(edge.to);
            if (relationGraph.containsById(source, target)) {
                remainingMatches.add(match);
            }
        }
        return remainingMatches;
    }

    // ===============================================================================================
    // ================================ Internal classes =============================================
    // ===============================================================================================

    static class NodeSet {
        private int nodeSet = 0;

        private static int toIndex(final Node node) {
            return 1 << node.id;
        }

        public boolean contains(final Node node) {
            return (nodeSet & toIndex(node)) != 0;
        }

        public boolean add(final Node node) {
            final int old = nodeSet;
            nodeSet |= toIndex(node);
            return old != nodeSet;
        }
    }

    static class EdgeQueue {
        private final Edge[] unmatchedEdges;
        private final int length;

        public EdgeQueue(final ViolationPattern pattern) {
            unmatchedEdges = pattern.edges.toArray(new Edge[0]);
            this.length = unmatchedEdges.length;
        }

        public Edge pop(final NodeSet visited) {
            Edge candidate = null;
            int index = -1;
            for (int i = 0; i < length; ++i) {
                final Edge e = unmatchedEdges[i];
                if (e != null) {
                    final boolean fromVisited = visited.contains(e.from);
                    final boolean toVisited = visited.contains(e.to);
                    if (fromVisited && toVisited) {
                        candidate = e;
                        index = i;
                        break;
                    } else if (candidate == null && (fromVisited || toVisited)) {
                        candidate = e;
                        index = i;
                    }
                }
            }

            if (candidate != null) {
                unmatchedEdges[index] = null;
            }
            return candidate;
        }
    }

    public record Node(int id) {
        @Override
        public String toString() { return "n#" + id; }

        @Override
        public boolean equals(Object obj) { return this == obj; }
    }

    public record Edge(RelationGraph graph, Node from, Node to, int id, boolean isNegated) {
        @Override
        public String toString() {
            return String.format("%s-%s->%s", from, graph == null ? "null" : graph.getName(), to);
        }
        @Override
        public boolean equals(final Object obj) { return this == obj; }
    }

    public static class Match {
        private final int[] nodeId2EventId;

        public int atNode(final Node node) { return nodeId2EventId[node.id]; }
        private void setMatch(final Node node, final int id) { nodeId2EventId[node.id] = id; }

        private Match(final int[] mapping) {
            this.nodeId2EventId = mapping;
        }

        public Match(final int size) {
            this.nodeId2EventId = new int[size];
            Arrays.fill(this.nodeId2EventId, -1);
        }

        public Match with(final Node node, final int match) {
            final int[] updatedMatching = Arrays.copyOf(this.nodeId2EventId, this.nodeId2EventId.length);
            updatedMatching[node.id] = match;
            return new Match(updatedMatching);
        }

        public int[] toArray() { return nodeId2EventId; }

        @Override
        public String toString() {
            final StringBuilder builder = new StringBuilder();
            for (int i = 0; i < nodeId2EventId.length; i++) {
                builder.append("[n")
                        .append(i)
                        .append("->")
                        .append(nodeId2EventId[i])
                        .append(']')
                        .append(", ");
            }
            builder.delete(builder.length() - 2, builder.length());
            return builder.toString();
        }
    }
}
