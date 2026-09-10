package com.dat3m.dartagnan.solver.caat.constraints;

import com.dat3m.dartagnan.solver.caat.domain.Domain;
import com.dat3m.dartagnan.solver.caat.predicates.CAATPredicate;
import com.dat3m.dartagnan.solver.caat.predicates.Derivable;
import com.dat3m.dartagnan.solver.caat.predicates.relationGraphs.Edge;
import com.dat3m.dartagnan.solver.caat.predicates.relationGraphs.RelationGraph;
import com.dat3m.dartagnan.solver.caat4wmm.ViolationPattern;
import com.google.common.base.Preconditions;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class IrreflexivityConstraint extends AbstractConstraint {

    private final RelationGraph constrainedGraph;
    private final List<Edge> violatingEdges = new ArrayList<>();
    private ViolationPattern pattern;

    public IrreflexivityConstraint(RelationGraph constrainedGraph) {
       this.constrainedGraph = constrainedGraph;
    }

    public IrreflexivityConstraint(RelationGraph constrainedGraph, ViolationPattern pattern) {
        this.constrainedGraph = constrainedGraph;
        this.pattern = pattern;
    }

    @Override
    public RelationGraph getConstrainedPredicate() {
        return constrainedGraph;
    }

    @Override
    public boolean checkForViolations() {
        return !violatingEdges.isEmpty();
    }

    @Override
    public List<List<Edge>> getViolations() {
        return violatingEdges.stream().map(Collections::singletonList).collect(Collectors.toList());
    }

    @Override
    public void onDomainInit(CAATPredicate predicate, Domain<?> domain) {
        super.onDomainInit(predicate, domain);
        violatingEdges.clear();
    }

    @Override
    public void onPopulation(CAATPredicate predicate) {
        Preconditions.checkArgument(predicate instanceof RelationGraph, "Relation graph expected");
        constrainedGraph.edgeStream().filter(Edge::isLoop).forEach(violatingEdges::add);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void onChanged(CAATPredicate predicate, Collection<? extends Derivable> added) {
        ((Collection<Edge>)added).stream().filter(Edge::isLoop).forEach(violatingEdges::add);
    }

    @Override
    public void onBacktrack(CAATPredicate predicate, int time) {
        violatingEdges.removeIf(e -> e.getTime() > time);
    }

    public Collection<List<GraphEdge>> getCyclicViolations() {
        final Collection<List<GraphEdge>> cycles = new ArrayList<>();
        final List<ViolationPattern.Edge> patternEdges = pattern.getEdges();
        for (final Edge violatingEdge : violatingEdges) {
            for (final ViolationPattern.Match cycle : pattern.findMatches(violatingEdge.getFirst(), violatingEdge.getSecond())) {
                final List<GraphEdge> edges = new ArrayList<>();
                final int[] cycleEventIds = cycle.toArray();
                for (final ViolationPattern.Edge edge : patternEdges) {
                    final int source = cycleEventIds[edge.from().id()];
                    final int target = cycleEventIds[edge.to().id()];
                    final RelationGraph graph = edge.graph();
                    edges.add(new GraphEdge(graph.getById(source, target), graph));
                }
                cycles.add(edges);
            }
        }
        return cycles;
    }

    public static class GraphEdge extends Edge {
        private final RelationGraph graph;

        private GraphEdge(Edge edge, RelationGraph graph) {
            super(edge.getFirst(), edge.getSecond(), edge.getTime(), edge.getDerivationLength());
            this.graph = graph;
        }

        public RelationGraph getGraph() {
            return graph;
        }
    }
}