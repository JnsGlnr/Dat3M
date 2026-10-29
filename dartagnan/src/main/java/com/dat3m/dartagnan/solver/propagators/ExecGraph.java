package com.dat3m.dartagnan.solver.propagators;

import org.sosy_lab.java_smt.api.BooleanFormula;

import java.util.*;

import static com.dat3m.dartagnan.solver.propagators.VarGraph.*;

public class ExecGraph {

    final int domainSize;
    final Map<BooleanFormula, ExecLiteral> var2Edge = new HashMap<>();

    private final List<ExecLiteral> trace = new ArrayList<>();
    private final List<Integer> backtrackPoints = new ArrayList<>();
    private int curLevel = 0;

    public ExecGraph(int domainSize) {
        this.domainSize = domainSize;
    }

    public ExecLiteral getEdge(BooleanFormula edgeVar) {
        return var2Edge.get(edgeVar);
    }

    public void addMustEdge(int eventId) {
        addVar(eventId, null);
    }

    public ExecLiteral addVar(int eventId, BooleanFormula edgeVar) {
        final ExecLiteral edge = new ExecLiteral(eventId, edgeVar);

        if (!edge.isMust()) {
            final ExecLiteral previousEdge = var2Edge.putIfAbsent(edgeVar, edge);
            if (previousEdge != null) {
                return previousEdge;
            }
        } else {
            edge.value = TRUE;
        }
        return edge;
    }

    public void push() {
        curLevel++;
        backtrackPoints.add(trace.size());
    }

    public void pop(int numLevels) {
        curLevel -= numLevels;

        int backtrackPoint = backtrackPoints.get(curLevel);
        backtrackPoints.subList(curLevel, backtrackPoints.size()).clear();
        trace.subList(backtrackPoint, trace.size()).forEach(this::unassignLiteral);
        trace.subList(backtrackPoint, trace.size()).clear();
    }

    public void assignLiteral(ExecLiteral e, int value) {
        assert value == TRUE || value == FALSE;
        assert !e.isMust();
        trace.add(e);
        e.value = value;
    }

    public void assignLiteral(ExecLiteral e, boolean value) {
        assignLiteral(e, value ? TRUE : FALSE);
    }

    private void unassignLiteral(ExecLiteral e) {
        assert !e.isMust();
        e.value = UNASSIGNED;
    }

    public final static class ExecLiteral {
        private final int eventId;
        private transient int value = UNASSIGNED;

        private final transient BooleanFormula edgeVar;

        public int getValue() { return value; }

        public ExecLiteral(int eventId, BooleanFormula edgeVar) {
            this.eventId = eventId;
            this.edgeVar = edgeVar;
        }

        public boolean isUnassigned() { return value == UNASSIGNED; }
        public boolean isTrue() { return value == TRUE; }
        public boolean isFalse() { return value == FALSE; }
        public boolean isMust() { return edgeVar == null; }

        @Override
        public String toString() {
            return "%s(%d)".formatted(edgeVar, eventId);
        }

        @Override
        public int hashCode() {
            return eventId;
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof ExecLiteral edge && edge.eventId == eventId;
        }
    }
}
