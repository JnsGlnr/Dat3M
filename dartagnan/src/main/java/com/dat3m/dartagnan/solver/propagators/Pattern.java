package com.dat3m.dartagnan.solver.propagators;

import java.util.*;

public class Pattern {

    final List<Node> nodes = new ArrayList<>();
    final List<Edge> edges = new ArrayList<>();

    private Edge[][] leftEdges = null;
    private Edge[][] rightEdges = null;

    private final List<List<Integer[]>> backtrackPoints = new ArrayList<>();
    private int curLevel = 0;

    List<Integer[]> partialMatches = new ArrayList<>();

    // ------------------------------------------------------------------------------------------
    // Construction

    public Pattern() {
    }

    public Node addPatternNode() {
        final Node node = new Node(nodes.size());
        nodes.add(node);
        return node;
    }

    public Edge addPatternEdge(final VarGraph graph, final Node from, final Node to) {
        final Edge edge = new Edge(graph, from, to, edges.size());
        edges.add(edge);
        return edge;
    }

    public Edge appendPatternEdge(final VarGraph graph, final boolean isInverse) {
        final Node lastNode = nodes.isEmpty() ? addPatternNode() : nodes.get(nodes.size() - 1);
        final Node newNode = addPatternNode();
        return addPatternEdge(graph, isInverse ? newNode : lastNode, isInverse ? lastNode : newNode);
    }

    public Edge appendLastPatternEdge(final VarGraph graph, final boolean isInverse) {
        final Node firstNode;
        final Node lastNode;
        if (nodes.isEmpty()) {
            firstNode = addPatternNode();
            lastNode = firstNode;
        } else {
            firstNode = nodes.get(0);
            lastNode = nodes.get(nodes.size() - 1);
        }
        return addPatternEdge(graph, isInverse ? firstNode : lastNode, isInverse ? lastNode : firstNode);
    }

    public void build() {
        final int numOfEdges = edges.size();
        leftEdges = new Edge[numOfEdges][];
        rightEdges = new Edge[numOfEdges][];
        for (int i = 0; i < edges.size(); ++i) {
            final Edge edge = edges.get(i);
            final Node from = edge.from;
            final Node to = edge.to;
            final List<Edge> leftEdges = new ArrayList<>();
            final List<Edge> rightEdges = new ArrayList<>();
            for (final Edge otherEdge : edges) {
                if (edge != otherEdge) {
                    if (otherEdge.from == from || otherEdge.to == from) {
                        leftEdges.add(otherEdge);
                    }
                    if (otherEdge.from == to || otherEdge.to == to) {
                        rightEdges.add(otherEdge);
                    }
                }
            }
            this.leftEdges[i] = leftEdges.toArray(new Edge[0]);
            this.rightEdges[i] = rightEdges.toArray(new Edge[0]);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Matching

    public record MatchingResult(List<Integer[]> matches, List<Integer[]> theoryPropagations) {
    }

    public MatchingResult addEdge(final Edge edge, int from, int to) {
        final int numOfNodes = nodes.size();
        final Node fromNode = edge.from;
        final Node toNode = edge.to;
        final int fromId = fromNode.id;
        final int toId = toNode.id;
        final int edgeId = edge.id;

        final List<Integer[]> newPartialMatches = new ArrayList<>();
        final List<Integer[]> newMatches = new ArrayList<>();
        final List<Integer[]> theoryPropagations = new ArrayList<>();
        for (Integer[] partialMatch : partialMatches) {
            final Integer fromEvent = partialMatch[fromId];
            if (fromEvent == null || fromEvent == from) {
                if (fromEvent == null) {
                    partialMatch = updateMatch(partialMatch, fromNode, from);
                }
                final Integer toEvent = partialMatch[toId];
                if (toEvent == null || toEvent == to) {
                    if (toEvent == null) {
                        partialMatch = updateMatch(partialMatch, toNode, to);
                    }

                    boolean isFullMatch = true;
                    for (int nodeId = 0; nodeId < numOfNodes; ++nodeId) {
                        if (partialMatch[nodeId] == null) {
                            isFullMatch = false;
                            break;
                        }
                    }

                    boolean isValid = true;
                    if (fromEvent == null) {
                        isValid = prune(fromNode, from, leftEdges[edgeId], partialMatch);
                    }
                    if (toEvent == null && isValid) {
                        isValid = prune(toNode, to, rightEdges[edgeId], partialMatch);
                    }

                    if (isValid) {
                        if (isFullMatch) {
                            newMatches.add(partialMatch);
                        } else {
                            newPartialMatches.add(partialMatch);
                        }
                    } else if (isFullMatch) {
                        theoryPropagations.add(partialMatch);
                    }
                }
            }
        }
        partialMatches.addAll(newPartialMatches);

        final Integer[] trivialPartialMatch = new Integer[numOfNodes];
        trivialPartialMatch[fromId] = from;
        trivialPartialMatch[toId] = to;
        final Edge[] ingoing = leftEdges[edgeId];
        final Edge[] outgoing = rightEdges[edgeId];
        if (prune(fromNode, from, ingoing, trivialPartialMatch) && prune(toNode, to, outgoing, trivialPartialMatch)) {
            if (numOfNodes == 2) {
                newMatches.add(trivialPartialMatch);
            } else {
                final List<Integer[]> leftMatches = new ArrayList<>();
                for (final Edge inEdge : ingoing) {
                    final Node inEdgeFrom = inEdge.from;
                    final Node inEdgeTo = inEdge.to;
                    if (inEdgeFrom == fromNode) {
                        if (numOfNodes == 3 && inEdgeTo != fromNode && inEdgeTo != toNode) {
                            newMatches.addAll(joinAlongLeft(inEdge, from, trivialPartialMatch, theoryPropagations));
                        } else {
                            leftMatches.addAll(joinAlongLeft(inEdge, from, trivialPartialMatch, null));
                        }
                    } else {
                        if (numOfNodes == 3 && inEdgeFrom != toNode) {
                            newMatches.addAll(joinAlongRight(inEdge, from, trivialPartialMatch, theoryPropagations));
                        } else {
                            leftMatches.addAll(joinAlongRight(inEdge, from, trivialPartialMatch, null));
                        }
                    }
                }
                final List<Integer[]> rightMatches = new ArrayList<>();
                for (final Edge outEdge : outgoing) {
                    final Node outEdgeFrom = outEdge.from;
                    final Node outEdgeTo = outEdge.to;
                    if (outEdgeFrom == toNode) {
                        if (numOfNodes == 3 && outEdgeTo != fromNode && outEdgeTo != toNode) {
                            newMatches.addAll(joinAlongLeft(outEdge, to, trivialPartialMatch, theoryPropagations));
                        } else {
                            final int outEdgeToId = outEdgeTo.id;
                            final Edge[] outEdgeToEdges = rightEdges[outEdge.id];
                            for (final Integer[] rightMatch : joinAlongLeft(outEdge, to, trivialPartialMatch, null)) {
                                rightMatches.add(rightMatch);
                                rightMatches.addAll(join(leftMatches, outEdgeTo, rightMatch[outEdgeToId], outEdgeToEdges));
                            }
                        }
                    } else {
                        if (numOfNodes == 3 && outEdgeFrom != fromNode) {
                            newMatches.addAll(joinAlongRight(outEdge, to, trivialPartialMatch, theoryPropagations));
                        } else {
                            final int outEdgeFromId = outEdgeFrom.id;
                            final Edge[] outEdgeFromEdges = leftEdges[outEdge.id];
                            for (final Integer[] rightMatch : joinAlongRight(outEdge, to, trivialPartialMatch, null)) {
                                rightMatches.add(rightMatch);
                                rightMatches.addAll(join(leftMatches, outEdgeFrom, rightMatch[outEdgeFromId], outEdgeFromEdges));
                            }
                        }
                    }
                }
                partialMatches.addAll(leftMatches);
                partialMatches.addAll(rightMatches);
            }
        } else if (numOfNodes == 2) {
            theoryPropagations.add(trivialPartialMatch);
        }

        return new MatchingResult(newMatches, theoryPropagations);
    }

    private List<Integer[]> joinAlongLeft(final Edge edge, final int matchedNode, final Integer[] match, final List<Integer[]> theoryPropagations) {
        final VarGraph graph = edge.graph;
        final Node edgeTo = edge.to;
        final List<Integer[]> newMatches = new ArrayList<>();
        for (final VarGraph.Edge edgeToEdge : graph.getTrueOutEdges(matchedNode)) {
            final int edgeToEvent = edgeToEdge.getTarget();
            final Integer[] newMatch = updateMatch(match, edgeTo, edgeToEvent);
            if (prune(edgeTo, edgeToEvent, rightEdges[edge.id], newMatch)) {
                newMatches.add(newMatch);
            } else if (theoryPropagations != null) {
                theoryPropagations.add(newMatch);
            }
        }
        return newMatches;
    }

    private List<Integer[]> joinAlongRight(final Edge edge, final int matchedNode, final Integer[] match, final List<Integer[]> theoryPropagations) {
        final VarGraph graph = edge.graph;
        final Node edgeFrom = edge.from;
        final List<Integer[]> newMatches = new ArrayList<>();
        for (final VarGraph.Edge edgeFromEdge : graph.getTrueInEdges(matchedNode)) {
            final int edgeFromEvent = edgeFromEdge.getSource();
            final Integer[] newMatch = updateMatch(match, edgeFrom, edgeFromEvent);
            if (prune(edgeFrom, edgeFromEvent, leftEdges[edge.id], newMatch)) {
                newMatches.add(newMatch);
            } else if (theoryPropagations != null) {
                theoryPropagations.add(newMatch);
            }
        }
        return newMatches;
    }

    private List<Integer[]> join(final List<Integer[]> matchesToJoinWith, final Node newNode, final int newEvent, final Edge[] newNodeEdges) {
        final List<Integer[]> newMatches = new ArrayList<>();
        final int newNodeId = newNode.id;
        MATCH_LOOP:
        for (final Integer[] match : matchesToJoinWith) {
            boolean producesFullMatch = true;
            for (int i = 0; i < match.length; ++i) {
                if (match[i] == null) {
                    if (i != newNodeId) {
                        producesFullMatch = false;
                        if (i > newNodeId) {
                            break;
                        }
                    } else if (!producesFullMatch) {
                        break;
                    }
                } else if (i == newNodeId) {
                    continue MATCH_LOOP;
                }
            }
            if (!producesFullMatch) {
                final Integer[] newMatch = updateMatch(match, newNode, newEvent);
                if (prune(newNode, newEvent, newNodeEdges, newMatch)) {
                    newMatches.add(newMatch);
                }
            }
        }
        return newMatches;
    }

    // TODO: Check the domain/range of nodes, e.g. amo, lxsx
    private boolean prune(final Node node, final int nodeEvent, final Edge[] edges, final Integer[] match) {
        for (final Edge patternEdge : edges) {
            final Node patternFrom = patternEdge.from;
            final Node patternTo = patternEdge.to;
            Integer matchedEvent;
            if (patternFrom == node && (matchedEvent = match[patternTo.id]) != null && !patternEdge.graph.contains(nodeEvent, matchedEvent)) {
                return false;
            }
            if (patternTo == node && (matchedEvent = match[patternFrom.id]) != null && !patternEdge.graph.contains(matchedEvent, nodeEvent)) {
                return false;
            }
        }
        return true;
    }

    private static Integer[] updateMatch(final Integer[] match, final Node node, final int event) {
        final Integer[] updatedMatching = Arrays.copyOf(match, match.length);
        updatedMatching[node.id] = event;
        return updatedMatching;
    }

    public void removeEdge(final Edge edge, final int from, final int to) {
        final int fromId = edge.from.id;
        final int toId = edge.to.id;
        partialMatches.removeIf(m -> {
            final Integer fromEvent = m[fromId];
            final Integer toEvent = m[toId];
            return fromEvent != null && fromEvent == from && toEvent != null && toEvent == to;
        });
    }

    public void push() {
        ++curLevel;
        backtrackPoints.add(partialMatches);
        final List<Integer[]> oldPartialMatches = partialMatches;
        partialMatches = new ArrayList<>();
        for (final Integer[] otherMatch : oldPartialMatches) {
            final Integer[] newMatch = new Integer[otherMatch.length];
            System.arraycopy(otherMatch, 0, newMatch, 0, newMatch.length);
            partialMatches.add(newMatch);
        }
    }

    public void pop(final int numLevels) {
        curLevel -= numLevels;
        partialMatches = backtrackPoints.get(curLevel);
        backtrackPoints.subList(curLevel, backtrackPoints.size()).clear();
    }

    // ===============================================================================================
    // ================================ Internal classes =============================================
    // ===============================================================================================

    public record Node(int id) {
        @Override
        public String toString() { return "n#" + id; }

        @Override
        public boolean equals(Object obj) { return this == obj; }
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
