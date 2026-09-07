package com.dat3m.dartagnan.solver.propagators;

import com.dat3m.dartagnan.program.event.Event;
import com.dat3m.dartagnan.utils.collections.IndexedDomain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static java.util.Collections.emptyList;

public class SimplePattern extends Pattern {

    // ------------------------------------------------------------------------------------------
    // Construction

    public SimplePattern(final IndexedDomain<Event> domain) {
        super(domain);
    }

    @Override
    public void build() {
    }

    // ------------------------------------------------------------------------------------------
    // Matching

    @Override
    @SuppressWarnings("ForLoopReplaceableByForEach")
    public MatchingResult addEdge(final Edge edge, int from, int to) {
        if (!edge.from().check(from) || !edge.to().check(to) || (edge.from() == edge.to() && from != to)) {
            return new MatchingResult(emptyList(), emptyList());
        }

        final NodeSet visited = new NodeSet();
        visited.add(edge.from());
        visited.add(edge.to());

        final Match firstMatch = new Match(nodes.size());
        firstMatch.setMatch(edge.from(), from);
        firstMatch.setMatch(edge.to(), to);

        List<Match> curMatches = new ArrayList<>();
        curMatches.add(firstMatch);

        final EdgeQueue queue = new EdgeQueue(this, edge);

        Edge nextEdgeToJoin;
        while ((nextEdgeToJoin = queue.pop(visited)) != null) {
            final boolean joinAtSource = visited.add(nextEdgeToJoin.to());
            final boolean joinAtTarget = visited.add(nextEdgeToJoin.from());
            final boolean doPrune = !(joinAtSource || joinAtTarget);

            if (doPrune) {
                curMatches = prune(curMatches, nextEdgeToJoin);
            } else {
                final Node matchedNode = joinAtSource ? nextEdgeToJoin.from() : nextEdgeToJoin.to();
                final Node unmatchedNode = joinAtSource ? nextEdgeToJoin.to() : nextEdgeToJoin.from();
                final List<Match> nextMatches = new ArrayList<>();
                for (int i = 0, matchSize = curMatches.size(); i < matchSize; ++i) {
                    final Match match = curMatches.get(i);
                    final int[] joinedNodeIds = joinAlong(match, matchedNode, nextEdgeToJoin);
                    for (final int nodeId : joinedNodeIds) {
                        if (nodeId != -1) {
                            if (unmatchedNode.check(nodeId)) {
                                nextMatches.add(match.with(unmatchedNode, nodeId));
                            }
                        }
                    }
                }
                curMatches = nextMatches;
            }

            if (curMatches.isEmpty()) {
                return new MatchingResult(emptyList(), emptyList());
            }
        }


        return Match.getMatchingResult(curMatches);
    }

    private int[] joinAlong(final Match partialMatch, final Node node, final Edge edge) {
        final int nodeMatch = partialMatch.atNode(node);
        final VarGraph relationGraph = edge.graph();
        return edge.from() == node ? relationGraph.getTrueOutIndices(nodeMatch) : relationGraph.getTrueInIndices(nodeMatch);
    }

    private List<Match> prune(final List<Match> matches, final Edge edge) {
        final VarGraph relationGraph = edge.graph();
        final List<Match> remainingMatches = new ArrayList<>();
        for (final Match match : matches) {
            final int source = match.atNode(edge.from());
            final int target = match.atNode(edge.to());
            if (relationGraph.contains(source, target)) {
                remainingMatches.add(match);
            } else if (!match.hadConflict) {
                remainingMatches.add(match);
                match.hadConflict = true;
            }
        }
        return remainingMatches;
    }

    @Override
    public void removeEdge(final Edge edge, final int from, final int to) {
    }

    @Override
    public void push() {
    }

    @Override
    public void pop(final int numLevels) {
    }

    // ===============================================================================================
    // ================================ Internal classes =============================================
    // ===============================================================================================

    static class NodeSet {
        private int nodeSet = 0;
        private int size = 0;

        private static int toIndex(final Node node) {
            return 1 << node.id();
        }

        public boolean contains(final Node node) {
            return (nodeSet & toIndex(node)) != 0;
        }

        public boolean add(final Node node) {
            final int old = nodeSet;
            nodeSet |= toIndex(node);
            if (old != nodeSet) {
                ++size;
                return true;
            }
            return false;
        }

        public int getSize() {
            return size;
        }
    }

    static class EdgeQueue {
        private final Edge[] unmatchedEdges;
        private final int length;

        public EdgeQueue(final SimplePattern pattern, final Edge exlcudedEdge) {
            unmatchedEdges = pattern.edges.toArray(new Edge[0]);
            unmatchedEdges[exlcudedEdge.id()] = null;
            this.length = unmatchedEdges.length;
        }

        public Edge pop(final NodeSet visited) {
            Edge candidate = null;
            int index = -1;
            for (int i = 0; i < length; ++i) {
                final Edge e = unmatchedEdges[i];
                if (e != null) {
                    final boolean fromVisited = visited.contains(e.from());
                    final boolean toVisited = visited.contains(e.to());
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

    public static class Match {
        private final int[] nodeId2EventId;
        private boolean hadConflict = false;

        public int atNode(final Node node) { return nodeId2EventId[node.id()]; }
        private void setMatch(final Node node, final int id) { nodeId2EventId[node.id()] = id; }

        private Match(final int[] mapping) {
            this.nodeId2EventId = mapping;
        }

        public Match(final int size) {
            this.nodeId2EventId = new int[size];
            Arrays.fill(this.nodeId2EventId, -1);
        }

        public Match with(final Node node, final int match) {
            final int[] updatedMatching = Arrays.copyOf(this.nodeId2EventId, this.nodeId2EventId.length);
            updatedMatching[node.id()] = match;
            return new Match(updatedMatching);
        }

        public int[] toArray() { return nodeId2EventId; }

        private static MatchingResult getMatchingResult(final List<Match> matches) {
            final List<int[]> realMatches = new ArrayList<>();
            final List<int[]> theoryPropagations = new ArrayList<>();
            for (final Match match : matches) {
                (match.hadConflict ? theoryPropagations : realMatches).add(match.nodeId2EventId);
            }
            return new MatchingResult(realMatches, theoryPropagations);
        }

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
