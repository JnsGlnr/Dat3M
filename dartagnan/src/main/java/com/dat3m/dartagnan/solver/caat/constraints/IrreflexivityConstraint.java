package com.dat3m.dartagnan.solver.caat.constraints;

import com.dat3m.dartagnan.encoding.ActiveSetAnalysis;
import com.dat3m.dartagnan.program.event.Event;
import com.dat3m.dartagnan.solver.caat.domain.Domain;
import com.dat3m.dartagnan.solver.caat.predicates.CAATPredicate;
import com.dat3m.dartagnan.solver.caat.predicates.Derivable;
import com.dat3m.dartagnan.solver.caat.predicates.relationGraphs.Edge;
import com.dat3m.dartagnan.solver.caat.predicates.relationGraphs.RelationGraph;
import com.dat3m.dartagnan.solver.caat.predicates.relationGraphs.derived.TransitiveGraph;
import com.dat3m.dartagnan.solver.caat4wmm.ExecutionGraph;
import com.dat3m.dartagnan.solver.caat4wmm.ViolationPattern;
import com.dat3m.dartagnan.wmm.Relation;
import com.dat3m.dartagnan.wmm.analysis.RelationAnalysis;
import com.dat3m.dartagnan.wmm.utils.graph.EventGraph;
import com.google.common.base.Preconditions;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static com.dat3m.dartagnan.solver.caat.misc.PathAlgorithm.findShortestPath;

public class IrreflexivityConstraint extends AbstractConstraint {

    private final RelationGraph constrainedGraph;
    private final List<Edge> violatingEdges = new ArrayList<>();
    private ViolationPattern pattern;
    private ExecutionGraph executionGraph;

    public IrreflexivityConstraint(RelationGraph constrainedGraph) {
       this.constrainedGraph = constrainedGraph;
    }

    public IrreflexivityConstraint(RelationGraph constrainedGraph, ViolationPattern pattern, ExecutionGraph graph) {
        this.constrainedGraph = constrainedGraph;
        this.pattern = pattern;
        this.executionGraph = graph;
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
                    final Edge graphEdge = graph.getById(source, target);
                    if (edge.graph() instanceof TransitiveGraph) {
                        final RelationGraph inner = (RelationGraph) graph.getDependencies().get(0);
                        final List<Edge> path = findShortestPath(inner, graphEdge.getFirst(), graphEdge.getSecond(),
                                pathEdge -> pathEdge.getDerivationLength() <= graphEdge.getDerivationLength() - 1
                                        && isActive(pathEdge, inner));
                        for (final Edge pathEdge : path) {
                            edges.add(new GraphEdge(pathEdge, inner));
                        }
                    } else {
                        edges.add(new GraphEdge(graph.getById(source, target), graph));
                    }
                }
                cycles.add(edges);
            }
        }
        return cycles;
    }

    private boolean isActive(final Edge edge, final RelationGraph graph) {
        final ActiveSetAnalysis asa = executionGraph.getAnalysisContext().requires(ActiveSetAnalysis.class);
        final RelationAnalysis ra = executionGraph.getAnalysisContext().requires(RelationAnalysis.class);
        final Relation rel = executionGraph.getRelation(graph);
        final EventGraph activeSet = asa.getActiveSet(rel.getDefinition());
        final EventGraph mustSet = ra.getKnowledge(rel).getMustSet();
        if (domain.getObjectById(edge.getFirst()) instanceof final Event source && domain.getObjectById(edge.getSecond()) instanceof final Event target) {
            return activeSet.contains(source, target) || mustSet.contains(source, target);
        }
        return true;
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