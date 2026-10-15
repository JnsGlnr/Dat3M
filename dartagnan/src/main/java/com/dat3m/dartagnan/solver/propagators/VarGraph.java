package com.dat3m.dartagnan.solver.propagators;

import org.sosy_lab.java_smt.api.BooleanFormula;
import org.sosy_lab.java_smt.api.BooleanFormulaManager;

import java.util.*;

public class VarGraph {

    final static int TRUE = 1;
    final static int UNASSIGNED = 0;
    final static int FALSE = -1;

    final int domainSize;
    final Map<BooleanFormula, Edge> var2Edge = new HashMap<>();
    private final List<Edge>[] preInEdges;
    private final List<Edge>[] preOutEdges;
    final Edge[][] inEdges;
    final Edge[][] outEdges;

    private final List<Edge> trace = new ArrayList<>();
    private final List<Integer> backtrackPoints = new ArrayList<>();
    private int curLevel = 0;

    private final BooleanFormulaManager bmgr;

    @SuppressWarnings("unchecked")
    public VarGraph(int domainSize, BooleanFormulaManager bmgr) {
        this.bmgr = bmgr;
        this.domainSize = domainSize;

        preInEdges = (List<Edge>[]) new List[domainSize];
        preOutEdges = (List<Edge>[]) new List[domainSize];
        for (int i = 0; i < domainSize; i++) {
            preInEdges[i] = new ArrayList<>();
            preOutEdges[i] = new ArrayList<>();
        }
        inEdges = new Edge[domainSize][domainSize];
        outEdges = new Edge[domainSize][domainSize];
    }

    public Edge getEdge(BooleanFormula edgeVar) {
        return var2Edge.get(edgeVar);
    }

    public Edge addMustEdge(int source, int target, BooleanFormula exec) {
        return addVarEdge(source, target, null, bmgr.not(exec));
    }

    public Edge addVarEdge(int source, int target, BooleanFormula edgeVar) {
        return addVarEdge(source, target, edgeVar, bmgr.not(edgeVar));
    }

    public Edge addVarEdge(int source, int target, BooleanFormula edgeVar, BooleanFormula negEdgeFormula) {
        final Edge edge = new Edge(source, target, edgeVar);
        preInEdges[target].add(edge);
        preOutEdges[source].add(edge);

        if (!edge.isMust()) {
            var2Edge.put(edgeVar, edge);
        }
        edge.negEdgeFormula = negEdgeFormula;
        return edge;
    }

    public void build() {
        for (int i = 0; i < domainSize; ++i) {
            final List<Edge> edges = preInEdges[i];
            for (int j = 0; j < edges.size(); ++j) {
                inEdges[i][j] = edges.get(j);
            }
        }
        for (int i = 0; i < domainSize; ++i) {
            final List<Edge> edges = preOutEdges[i];
            for (int j = 0; j < edges.size(); ++j) {
                outEdges[i][j] = edges.get(j);
            }
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
        e.value = UNASSIGNED;
    }

    public Edge[] getInEdges(int i) {
        return inEdges[i];
    }

    public Iterable<Edge> getTrueOutEdges(int i) {
        final var it = new EnabledIterator(outEdges[i]);
        return () -> it;
    }

    private final static class EnabledIterator implements Iterator<Edge> {
        private final Edge[] edges;
        private int index = -1;

        public EnabledIterator(Edge[] edges) {
            this.edges = edges;
            advance();
        }

        private void advance() {
            final Edge[] edges = this.edges;
            final int size = edges.length;
            int index = this.index;
            do {
                index++;
            } while (index < size && (edges[index] == null || !edges[index].isTrue()));
            this.index = index;
        }

        @Override
        public boolean hasNext() {
            return index < edges.length;
        }

        @Override
        public Edge next() {
            Edge e = edges[index];
            advance();
            return e;
        }
    }

    public final static class Edge {
        private final int source;
        private final int target;
        private transient int value = UNASSIGNED;

        private final transient BooleanFormula edgeVar;
        private transient BooleanFormula negEdgeFormula;

        public int getSource() { return source; }
        public int getTarget() { return target; }
        public BooleanFormula getEdgeVar() { return edgeVar; }
        public BooleanFormula getNegEdgeFormula() { return negEdgeFormula; }
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
