package com.dat3m.dartagnan.solver.propagators;

import com.dat3m.dartagnan.wmm.Relation;
import org.sosy_lab.java_smt.api.BooleanFormula;
import org.sosy_lab.java_smt.api.BooleanFormulaManager;

import java.util.*;

public class VarGraph {

    final static int TRUE = 1;
    final static int UNASSIGNED = 0;
    final static int FALSE = -1;

    final int domainSize;
    final Map<BooleanFormula, Edge> var2Edge = new HashMap<>();
    final List<Edge>[] inEdges;
    final List<Edge>[] outEdges;
    final Edge[][] edgeMatrix;
    final List<Edge> allEdges = new ArrayList<>();
    final List<Edge> dynamicEdges = new ArrayList<>();

    private final List<Edge> trace = new ArrayList<>();
    private final List<Integer> backtrackPoints = new ArrayList<>();
    private int curLevel = 0;

    private final Relation rel;
    private final BooleanFormulaManager bmgr;

    @SuppressWarnings("unchecked")
    public VarGraph(Relation rel, int domainSize, BooleanFormulaManager bmgr) {
        this.rel = rel;
        this.bmgr = bmgr;
        this.domainSize = domainSize;

        inEdges = (List<Edge>[]) new List[domainSize];
        outEdges = (List<Edge>[]) new List[domainSize];
        edgeMatrix = new Edge[domainSize][domainSize];
        for (int i = 0; i < domainSize; i++) {
            inEdges[i] = new ArrayList<>();
            outEdges[i] = new ArrayList<>();
        }
    }

    public String getName() {
        return rel.getNameOrTerm();
    }

    public Edge getEdge(BooleanFormula edgeVar) {
        return var2Edge.get(edgeVar);
    }

    public void addMustEdge(int source, int target) {
        addVarEdge(source, target, null);
    }

    public void addVarEdge(int source, int target, BooleanFormula edgeVar) {
        final Edge edge = new Edge(source, target, edgeVar);
        inEdges[target].add(edge);
        outEdges[source].add(edge);
        edgeMatrix[source][target] = edge;
        allEdges.add(edge);

        if (!edge.isMust()) {
            var2Edge.put(edgeVar, edge);
            edge.negEdgeVar = bmgr.not(edgeVar);
            dynamicEdges.add(edge);
        } else {
            edge.value = TRUE;
        }
    }

    public void push() {
        curLevel++;
        backtrackPoints.add(trace.size());
    }

    public void pop(int numLevels) {
        curLevel -= numLevels;

        int backtrackPoint = backtrackPoints.get(curLevel);
        backtrackPoints.subList(curLevel, backtrackPoints.size()).clear();
        trace.subList(backtrackPoint, trace.size()).forEach(this::unassignEdge);
        trace.subList(backtrackPoint, trace.size()).clear();
    }

    public void assignEdge(Edge e, int value) {
        assert value == TRUE || value == FALSE;
        trace.add(e);
        e.value = value;
    }

    public void assignEdge(Edge e, boolean value) {
        assignEdge(e, value ? TRUE : FALSE);
    }

    private void unassignEdge(Edge e) {
        assert !e.isMust();
        e.value = UNASSIGNED;
    }

    public Iterable<Edge> getTrueInEdges(int i) {
        final var it = new EnabledIterator(inEdges[i]);
        return () -> it;
    }

    public Iterable<Edge> getTrueOutEdges(int i) {
        final var it = new EnabledIterator(outEdges[i]);
        return () -> it;
    }

    public int[] getTrueInIndices(int index) {
        final List<Edge> inEdges = this.inEdges[index];
        final int[] inIndices = new int[inEdges.size()];
        int i = 0;
        for (final Edge edge : inEdges) {
            if (edge.isTrue()) {
                inIndices[i++] = edge.getSource();
            } else {
                inIndices[i++] = -1;
            }
        }
        return inIndices;
    }

    public int[] getTrueOutIndices(int index) {
        final List<Edge> outEdges = this.outEdges[index];
        final int[] outIndices = new int[outEdges.size()];
        int i = 0;
        for (final Edge edge : outEdges) {
            if (edge.isTrue()) {
                outIndices[i++] = edge.getTarget();
            } else {
                outIndices[i++] = -1;
            }
        }
        return outIndices;
    }

    public boolean contains(int e1, int e2) {
        return containsWithEdge(e1, e2) != null;
    }

    public Edge containsWithEdge(int e1, int e2) {
        final Edge edge = edgeMatrix[e1][e2];
        if (edge != null && edge.isTrue()) {
            return edge;
        }
        return null;
    }

    public Edge getEdge(int e1, int e2) {
        final Edge edge = edgeMatrix[e1][e2];
        if (edge == null) {
            throw new IllegalArgumentException("Edge does not exist");
        }
        return edge;
    }

    @Override
    public String toString() {
        return getName();
    }

    private final static class EnabledIterator implements Iterator<Edge> {
        private final List<Edge> edges;
        private int index = -1;

        public EnabledIterator(List<Edge> edges) {
            this.edges = edges;
            advance();
        }

        private void advance() {
            final List<Edge> edges = this.edges;
            final int size = edges.size();
            int index = this.index;
            do {
                index++;
            } while (index < size && !edges.get(index).isTrue());
            this.index = index;
        }

        @Override
        public boolean hasNext() {
            return index < edges.size();
        }

        @Override
        public Edge next() {
            Edge e = edges.get(index);
            advance();
            return e;
        }
    }

    public final static class Edge {
        private final int source;
        private final int target;
        private transient int value = UNASSIGNED;

        private final transient BooleanFormula edgeVar;
        private transient BooleanFormula negEdgeVar;

        public int getSource() { return source; }
        public int getTarget() { return target; }
        public BooleanFormula getEdgeVar() { return edgeVar; }
        public BooleanFormula getNegEdgeVar() { return negEdgeVar; }
        public int getValue() { return value; }

        public Edge(int source, int target, BooleanFormula edgeVar) {
            this.source = source;
            this.target = target;
            this.edgeVar = edgeVar;
        }

        public boolean isUnassigned() { return value == UNASSIGNED; }
        public boolean isTrue() { return value == TRUE; }
        public boolean isFalse() { return value == FALSE; }
        public boolean isMust() { return edgeVar == null; }

        @Override
        public String toString() {
            return "%s(%d, %d)".formatted(edgeVar, source, target);
        }

        @Override
        public int hashCode() {
            return ((target << 14) - 1) + source;
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof Edge edge && edge.source == source && edge.target == target;
        }
    }
}
