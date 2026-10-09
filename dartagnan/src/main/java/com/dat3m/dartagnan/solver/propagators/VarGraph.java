package com.dat3m.dartagnan.solver.propagators;

import com.dat3m.dartagnan.program.event.Event;
import com.dat3m.dartagnan.utils.collections.IndexedDomain;
import com.dat3m.dartagnan.utils.collections.IndexedSet;
import org.sosy_lab.java_smt.api.BooleanFormula;
import org.sosy_lab.java_smt.api.BooleanFormulaManager;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Stream;

public class VarGraph {

    final static int TRUE = 1;
    final static int UNASSIGNED = 0;
    final static int FALSE = -1;

    final IndexedDomain<Event> domain;
    final Map<BooleanFormula, Edge> var2Edge = new HashMap<>();
    final List<Edge>[] inEdges;
    final List<Edge>[] outEdges;

    private final List<Edge> trace = new ArrayList<>();
    private final List<Integer> backtrackPoints = new ArrayList<>();
    private int curLevel = 0;

    private final BooleanFormulaManager bmgr;

    @SuppressWarnings("unchecked")
    public VarGraph(IndexedDomain<Event> domain, BooleanFormulaManager bmgr) {
        this.bmgr = bmgr;
        this.domain = domain;

        inEdges = (List<Edge>[]) new List[domain.size()];
        outEdges = (List<Edge>[]) new List[domain.size()];
        for (int i = 0; i < domain.size(); i++) {
            inEdges[i] = new ArrayList<>();
            outEdges[i] = new ArrayList<>();
        }
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
        inEdges[target].add(edge);
        outEdges[source].add(edge);

        if (!edge.isMust()) {
            var2Edge.put(edgeVar, edge);
        }
        edge.negEdgeFormula = negEdgeFormula;
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

    public Iterable<Edge> getInEdges(int i) {
        return inEdges[i];
    }

    public Iterable<Edge> getTrueOutEdges(int i) {
        final var it = new FilteredIterator(outEdges[i], VarGraph::isEnabledEdge);
        return () -> it;
    }

    // Given `irreflexive graph; this+` and a `graph`-edge learned by the SMT solver.
    // Collect all unassigned edges that would close a cycle with the new edge.
    public Map<Edge, List<Edge>> propagateOther(Edge s_graph_t) {
        final var result = new HashMap<Edge, List<Edge>>();
        final var s = domain.unitSet(s_graph_t.source);
        final var t = domain.unitSet(s_graph_t.target);
        final var t_ = trueReachable(t, false);
        final var _s = trueReachable(s, true);
        for (Edge u_r : filterUnassigned(t_, _s)) {
            final var u = domain.unitSet(u_r.source);
            final var r = domain.unitSet(u_r.target);
            final var t_u = anyTruePath(t, u);
            final var r_s = anyTruePath(r, s);
            result.put(u_r, path(r_s, s_graph_t, t_u));
        }
        return result;
    }

    public Map<Edge, List<Edge>> propagate(Edge s_t, Collection<VarGraph> graphs) {
        final VarGraph union = graphs.size() == 1 ? graphs.iterator().next() : new VarGraph(domain, bmgr);
        if (graphs.size() != 1) {
            for (VarGraph graph : graphs) {
                for (int i = 0; i < domain.size(); i++) {
                    union.inEdges[i].addAll(graph.inEdges[i]);
                    union.outEdges[i].addAll(graph.outEdges[i]);
                }
            }
        }
        return propagate(s_t, union);
    }

    // Given `irreflexive graph; this+`.
    // Given a new edge learned by the SMT solver.
    // Collect all unassigned edges that would close a cycle with the new edge.
    public Map<Edge, List<Edge>> propagate(Edge s_t, VarGraph graph) {
        final var result = new HashMap<Edge, List<Edge>>();
        final var s = domain.unitSet(s_t.source);
        final var t = domain.unitSet(s_t.target);
        final var t_ = this.trueReachable(t, false);
        final var _s = this.trueReachable(s, true);
        final var t_graph = graph.trueAdjacent(t_, false);
        final var graph_s = graph.trueAdjacent(_s, true);
        final var t_graph_ = this.trueReachable(t_graph, false);
        final var _graph_s = this.trueReachable(graph_s, true);
        for (Edge u_graph_r : graph.filterUnassigned(t_, _s)) {
            final var u = domain.unitSet(u_graph_r.source);
            final var r = domain.unitSet(u_graph_r.target);
            final var r_s = anyTruePath(r, s);
            final var t_u = anyTruePath(t, u);
            result.put(u_graph_r, path(r_s, s_t, t_u));
        }
        for (Edge v_p : filterUnassigned(t_, _graph_s)) {
            final var v = domain.unitSet(v_p.source);
            final var p = domain.unitSet(v_p.target);
            final var t_v = anyTruePath(t, v);
            final var p_q = anyTruePath(p, graph_s);
            final var q = domain.unitSet(p_q.target);
            final var q_graph_r = graph.anyTrueEdge(q, _s);
            final var r = domain.unitSet(q_graph_r.target);
            final var r_s = anyTruePath(r, s);
            result.put(v_p, path(p_q, q_graph_r, r_s, s_t, t_v));
        }
        for (Edge w_r : filterUnassigned(t_graph_, _s)) {
            if (result.containsKey(w_r)) {
                continue;
            }
            final var w = domain.unitSet(w_r.source);
            final var r = domain.unitSet(w_r.target);
            final var v_w = anyTruePath(t_graph, w);
            final var v = domain.unitSet(v_w.source);
            final var u_graph_v = graph.anyTrueEdge(t_, v);
            final var u = domain.unitSet(u_graph_v.source);
            final var t_u = anyTruePath(t, u);
            final var r_s = anyTruePath(r, s);
            result.put(w_r, path(r_s, s_t, t_u, u_graph_v, v_w));
        }
        return result;
    }

    private static List<Edge> path(Path a, Edge b, Path c) {
        return Stream.of(a.edges(), List.of(b), c.edges()).flatMap(List::stream).toList();
    }

    private static List<Edge> path(Path a, Edge b, Path c, Edge d, Path e) {
        return Stream.of(a.edges(), List.of(b), c.edges(), List.of(d), e.edges()).flatMap(List::stream).toList();
    }

    private IndexedSet<Event> trueAdjacent(IndexedSet<Event> from, boolean in) {
        final var adjacent = domain.newSet();
        addTrueAdjacent(adjacent, from.toIndexArray(), in);
        return adjacent;
    }

    private IndexedSet<Event> trueReachable(IndexedSet<Event> start, boolean in) {
        final var reachable = domain.newSet();
        final var news = domain.newSet(start);
        while (!news.isEmpty()) {
            reachable.addAll(news);
            final int[] current = news.toIndexArray();
            news.clear();
            addTrueAdjacent(news, current, in);
            news.removeAll(reachable);
        }
        return reachable;
    }

    private void addTrueAdjacent(IndexedSet<Event> adjacent, int[] start, boolean in) {
        for (int i : start) {
            for (Edge e : (in ? inEdges : outEdges)[i]) {
                if (e.value == TRUE) {
                    adjacent.exchange(in ? e.source : e.target, true);
                }
            }
        }
    }

    private Collection<Edge> filterUnassigned(IndexedSet<Event> from, IndexedSet<Event> to) {
        final var edges = new ArrayList<Edge>();
        final boolean in = to.size() < from.size();
        for (int i : (in ? to : from).toIndexArray()) {
            for (Edge edge : (in ? inEdges : outEdges)[i]) {
                if (edge.value == UNASSIGNED && (in ? from : to).test(in ? edge.source : edge.target)) {
                    edges.add(edge);
                }
            }
        }
        return edges;
    }

    private Path anyTruePath(IndexedSet<Event> from, IndexedSet<Event> to) {
        final var path = new ArrayList<Edge>();
        // The top element only contains the most distant elements.
        // Every set beneath the top element is monotonously increasing.
        final Deque<IndexedSet<Event>> stackFrom = new ArrayDeque<>();
        final Deque<IndexedSet<Event>> stackTo = new ArrayDeque<>();
        while (from.disjoint(to)) {
            final boolean in = to.size() < from.size();
            final IndexedSet<Event> current = in ? to : from;
            final Deque<IndexedSet<Event>> stack = in ? stackTo : stackFrom;
            final IndexedSet<Event> next = trueAdjacent(current, in);
            if (!stack.isEmpty()) {
                current.addAll(stack.peek());
            }
            next.removeAll(current);
            stack.push(current);
            if (in) {
                to = next;
            } else {
                from = next;
            }
        }
        final IndexedSet<Event> intermediate = new IndexedSet<>(to);
        intermediate.retainAll(from);
        int source, target;
        source = target = intermediate.toIndexArray()[0];
        while (!stackFrom.isEmpty()) {
            final Edge edge = anyTrueEdge(stackFrom.pop(), domain.unitSet(source));
            path.add(edge);
            source = edge.source;
        }
        Collections.reverse(path);
        while (!stackTo.isEmpty()) {
            final Edge edge = anyTrueEdge(domain.unitSet(target), stackTo.pop());
            path.add(edge);
            target = edge.target;
        }
        return new Path(source, target, path);
    }

    private Edge anyTrueEdge(IndexedSet<Event> from, IndexedSet<Event> to) {
        final boolean in = to.size() < from.size();
        for (int i : (in ? to : from).toIndexArray()) {
            for (Edge edge : (in ? inEdges : outEdges)[i]) {
                if (edge.value == TRUE && (in ? from : to).test(in ? edge.source : edge.target)) {
                    return edge;
                }
            }
        }
        throw new NoSuchElementException();
    }

    private record Path(int source, int target, List<Edge> edges) {}

    private static boolean isEnabledEdge(Edge e) {
        return e.value == TRUE;
    }

    private final static class FilteredIterator implements Iterator<Edge> {
        private final List<Edge> edges;
        private final Predicate<Edge> filter;
        private int index = -1;

        public FilteredIterator(List<Edge> edges, Predicate<Edge> filter) {
            this.edges = edges;
            this.filter = filter;
            advance();
        }

        private void advance() {
            final List<Edge> edges = this.edges;
            final Predicate<Edge> filter = this.filter;
            final int size = edges.size();
            int index = this.index;
            do {
                index++;
            } while (index < size && !filter.test(edges.get(index)));
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
