package com.dat3m.dartagnan.solver.propagators;

import com.dat3m.dartagnan.encoding.EncodingContext;
import com.dat3m.dartagnan.encoding.WmmEncoder;
import com.dat3m.dartagnan.program.analysis.EventDomainRepository;
import com.dat3m.dartagnan.program.analysis.ExecutionAnalysis;
import com.dat3m.dartagnan.program.event.Event;
import com.dat3m.dartagnan.utils.collections.IndexedDomain;
import com.dat3m.dartagnan.wmm.Relation;
import com.dat3m.dartagnan.wmm.analysis.RelationAnalysis;
import com.dat3m.dartagnan.wmm.axiom.Irreflexivity;
import com.dat3m.dartagnan.wmm.utils.graph.EventGraph;
import org.sosy_lab.java_smt.api.BooleanFormula;
import org.sosy_lab.java_smt.api.PropagatorBackend;
import org.sosy_lab.java_smt.basicimpl.AbstractUserPropagator;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

public class AlmostAcyclicityPropagator extends AbstractUserPropagator {

    private static final boolean enableTheoryPropagation = true;
    // Might weaken propagation/learning if set to true?
    private static final boolean stopOnConflict = false;
    // If set to true, we can propagate the same edge twice but with different reasons
    private static final boolean allowDuplicatePropagation = false;

    private final RelationAnalysis relationAnalysis;
    private final ExecutionAnalysis executionAnalysis;
    private final EncodingContext context;
    private final WmmEncoder wmmEncoder;
    private final List<Case> cases = new ArrayList<>();
    private final Map<BooleanFormula, FormulaData> lit2Formula = new HashMap<>();
    private final IndexedDomain<Event> domain;
    private final ExecGraph exec;
    private final Map<BooleanFormula, List<PartiallyResolvedMustEdge>> execToMustEdges;
    private final Map<VarGraph.Edge, EdgeExecFormula> mustEdgeToExec;

    // -------- Dynamic search data --------
    private int curLevel = 0;
    private boolean raisedConflict = false;

    private final Queue<AlmostAcyclicityNode> workqueue = new ArrayDeque<>(); // Used for BFS
    private final AlmostAcyclicityReason[] ingoingMap; // Spanning tree for forward search
    private final AlmostAcyclicityReason[] outgoingMap; // Spanning tree for backward search

    // TODO: Evaluate the need for this.
    // Track already-made propagations to avoid redundant propagation
    private Set<VarGraph.Edge> alreadyPropagatedEdges;

    // -------- Misc --------
    // Used to cheaply associate data with BooleanFormulas
    private CachingFormulaMap<FormulaData> formulaLookup;

    // -------- Statistics --------
    private final Map<Set<BooleanFormula>, Integer> observedReasons = new HashMap<>();
    private int numPropagations = 0;
    private long numChecks = 0;


    public AlmostAcyclicityPropagator(WmmEncoder wmmEncoder, EncodingContext ctx) {
        this.context = ctx;
        this.relationAnalysis = ctx.getAnalysisContext().requires(RelationAnalysis.class);
        this.executionAnalysis = ctx.getAnalysisContext().requires(ExecutionAnalysis.class);
        this.wmmEncoder = wmmEncoder;

        this.domain = ctx.getAnalysisContext().requires(EventDomainRepository.class)
                .getDomain(EventDomainRepository.DomainBound.VISIBLE);
        this.exec = new ExecGraph(domain.size());
        ingoingMap = new AlmostAcyclicityReason[domain.size()];
        outgoingMap = new AlmostAcyclicityReason[domain.size()];
        execToMustEdges = new LinkedHashMap<>(domain.size() * 4 / 3);
        mustEdgeToExec = new HashMap<>();
    }

    public record PropagatableIrreflexivity(Irreflexivity axiom, Relation transitive, Relation other) {
    }

    public void registerAxiom(PropagatableIrreflexivity axiom) {
        final VarGraph transitiveGraph = new VarGraph(domain, context.getBooleanFormulaManager());
        final VarGraph otherGraph = new VarGraph(domain, context.getBooleanFormulaManager());
        final Case c = new Case(axiom.transitive, axiom.other, transitiveGraph, otherGraph);
        cases.add(c);
    }

    @Override
    public void initializeWithBackend(PropagatorBackend backend) {
        super.initializeWithBackend(backend);

        backend.notifyOnKnownValue();

        AtomicInteger numDynamicEdges = new AtomicInteger();
        final Map<BooleanFormula, FormulaData> mustFormulas = new HashMap<>();
        final Map<Relation, Collection<OtherGraph>> otherGraphs = new HashMap<>();
        for (Case c : cases) {
            final Relation transitive = c.transitive;
            final VarGraph transitiveGraph = c.transitiveGraph();
            final VarGraph otherGraph = c.otherGraph();

            if (!otherGraphs.containsKey(transitive)) {
                final Collection<OtherGraph> otherGraphsForTransitive = new ArrayList<>();
                otherGraphsForTransitive.add(new OtherGraph(otherGraph, false));
                otherGraphs.put(transitive, otherGraphsForTransitive);

                final EventGraph must = relationAnalysis.getKnowledge(transitive).getMustSet();
                final EventGraph activeSet = wmmEncoder.getActiveSet(transitive.getDefinition());
                activeSet.apply((x, y) -> {
                    final int idx = domain.indexOf(x);
                    final int idy = domain.indexOf(y);
                    final BooleanFormula edgeLit = context.edgeVariable(transitive.getNameOrTerm(), x, y);
                    if (must.contains(x, y)) {
                        final EdgeExecFormula edgeExec;
                        if (executionAnalysis.isImplied(x, y)) {
                            edgeExec = registerSingleExecEdge(x, idx, edgeLit, backend);
                        } else if (executionAnalysis.isImplied(y, x)) {
                            edgeExec = registerSingleExecEdge(y, idy, edgeLit, backend);
                        } else {
                            final BooleanFormula execLitX = context.execution(x);
                            final BooleanFormula execLitY = context.execution(y);
                            final ExecGraph.ExecLiteral execX = exec.addVar(idx, execLitX);
                            final ExecGraph.ExecLiteral execY = exec.addVar(idy, execLitY);
                            final PartiallyResolvedMustEdge remainingX = new PartiallyResolvedMustEdge(edgeLit, execY);
                            final PartiallyResolvedMustEdge remainingY = new PartiallyResolvedMustEdge(edgeLit, execX);
                            execToMustEdges.computeIfAbsent(execLitX, k -> new ArrayList<>()).add(remainingX);
                            execToMustEdges.computeIfAbsent(execLitY, k -> new ArrayList<>()).add(remainingY);
                            backend.registerExpression(execLitX);
                            backend.registerExpression(execLitY);
                            edgeExec = new EdgeExecFormula(execLitX, execLitY);
                        }
                        final BooleanFormula exec = context.execution(x, y);
                        final VarGraph.Edge edge = transitiveGraph.addMustEdge(idx, idy, exec);
                        mustEdgeToExec.put(edge, edgeExec);
                        mustFormulas.put(edgeLit, new FormulaData(transitiveGraph, edge, otherGraphsForTransitive));
                    } else {
                        final VarGraph.Edge edge = transitiveGraph.addVarEdge(idx, idy, edgeLit);
                        lit2Formula.put(edgeLit, new FormulaData(transitiveGraph, edge, otherGraphsForTransitive));
                        backend.registerExpression(edgeLit);
                    }
                    numDynamicEdges.getAndIncrement();
                });
            }

            final Relation other = c.other;
            if (!otherGraphs.containsKey(other)) {
                final Collection<OtherGraph> otherGraphsForOther = new ArrayList<>();
                otherGraphsForOther.add(new OtherGraph(transitiveGraph, true));
                otherGraphs.put(transitive, otherGraphsForOther);

                final EventGraph must = relationAnalysis.getKnowledge(other).getMustSet();
                final EventGraph activeSet = wmmEncoder.getActiveSet(other.getDefinition());
                activeSet.apply((x, y) -> {
                    final int idx = domain.indexOf(x);
                    final int idy = domain.indexOf(y);
                    final BooleanFormula edgeLit = context.edgeVariable(other.getNameOrTerm(), x, y);
                    if (must.contains(x, y)) {
                        final EdgeExecFormula edgeExec;
                        if (executionAnalysis.isImplied(x, y)) {
                            edgeExec = registerSingleExecEdge(x, idx, edgeLit, backend);
                        } else if (executionAnalysis.isImplied(y, x)) {
                            edgeExec = registerSingleExecEdge(y, idy, edgeLit, backend);
                        } else {
                            final BooleanFormula execLitX = context.execution(x);
                            final BooleanFormula execLitY = context.execution(y);
                            final ExecGraph.ExecLiteral execX = exec.addVar(idx, execLitX);
                            final ExecGraph.ExecLiteral execY = exec.addVar(idy, execLitY);
                            final PartiallyResolvedMustEdge remainingX = new PartiallyResolvedMustEdge(edgeLit, execY);
                            final PartiallyResolvedMustEdge remainingY = new PartiallyResolvedMustEdge(edgeLit, execX);
                            execToMustEdges.computeIfAbsent(execLitX, k -> new ArrayList<>()).add(remainingX);
                            execToMustEdges.computeIfAbsent(execLitY, k -> new ArrayList<>()).add(remainingY);
                            backend.registerExpression(execLitX);
                            backend.registerExpression(execLitY);
                            edgeExec = new EdgeExecFormula(execLitX, execLitY);
                        }
                        final BooleanFormula exec = context.execution(x, y);
                        VarGraph.Edge edge = otherGraph.addMustEdge(idx, idy, exec);
                        mustEdgeToExec.put(edge, edgeExec);
                        mustFormulas.put(edgeLit, new FormulaData(otherGraph, edge, otherGraphsForOther));
                    } else {
                        final VarGraph.Edge edge = otherGraph.addVarEdge(idx, idy, edgeLit);
                        lit2Formula.put(edgeLit, new FormulaData(otherGraph, edge, otherGraphsForOther));
                        backend.registerExpression(edgeLit);
                    }
                    numDynamicEdges.getAndIncrement();
                });
            }
        }

        formulaLookup = new CachingFormulaMap<>(numDynamicEdges.get() * 2, mustFormulas, lit2Formula::get);
        alreadyPropagatedEdges = Collections.newSetFromMap(new IdentityHashMap<>(numDynamicEdges.get()));
    }

    private EdgeExecFormula registerSingleExecEdge(Event event, int id, BooleanFormula edgeLit, PropagatorBackend backend) {
        final BooleanFormula execLit = context.execution(event);
        exec.addVar(id, execLit);
        final PartiallyResolvedMustEdge remaining = new PartiallyResolvedMustEdge(edgeLit, null);
        execToMustEdges.computeIfAbsent(execLit, k -> new ArrayList<>()).add(remaining);
        backend.registerExpression(execLit);
        return new EdgeExecFormula(execLit, null);
    }

    @Override
    public void onPush() {
        curLevel++;
        exec.push();
        cases.forEach(c -> {
            c.transitiveGraph.push();
            c.otherGraph.push();
        });
        // System.out.println("------- Push: " + curLevel + " -------");
    }

    @Override
    public void onPop(int numPoppedLevels) {
        raisedConflict = false;
        curLevel -= numPoppedLevels;
        exec.pop(numPoppedLevels);
        cases.forEach(c -> {
            c.transitiveGraph.pop(numPoppedLevels);
            c.otherGraph.pop(numPoppedLevels);
        });
        alreadyPropagatedEdges.clear();
        // System.out.println("------- Pop to: " + curLevel + " -------");
    }

    @Override
    public void onKnownValue(BooleanFormula expr, boolean value) {
        if (raisedConflict && stopOnConflict) {
            // We have a pending conflict
            // System.out.println("Already conflict; skip " + expr);
            return;
        }

        final List<PartiallyResolvedMustEdge> mustEdges = execToMustEdges.get(expr);
        if (mustEdges == null) {
            onKnownEdgeValue(expr, value);
        } else {
            exec.assignLiteral(exec.getEdge(expr), value);
            for (final PartiallyResolvedMustEdge mustData : mustEdges) {
                final BooleanFormula mustExpr = mustData.mustEdge();
                final ExecGraph.ExecLiteral otherExec = mustData.remainingExec();
                if (otherExec == null) {
                    onKnownEdgeValue(mustExpr, value);
                } else if (!otherExec.isUnassigned()) {
                    onKnownEdgeValue(mustExpr, value && otherExec.isTrue());
                }
            }
        }
    }

    public void onKnownEdgeValue(BooleanFormula expr, boolean value) {
        final FormulaData data = formulaLookup.get(expr);
        final VarGraph graph = data.graph();
        final VarGraph.Edge edge = data.edge();
        if (edge.isUnassigned() || value != edge.isTrue()) {
            graph.assignEdge(edge, value);

            if (value) {
                if (alreadyPropagatedEdges.contains(edge)) {
                    raisedConflict = true;
                    // System.out.println("Propagation conflict");
                    return;
                }
                processEdgeAddition(graph, data.other(), edge);

                numChecks++;
                if (numChecks % 1000000 == 0) {
                    System.out.println("numChecks: " + numChecks);
                    printStatistics();
                }
            }
        }
    }

    // Checks for cycles caused by adding <edge> and possibly raises a conflict.
    // If no conflict is raised, tries to do theory propagation
    private void processEdgeAddition(VarGraph graph, Collection<OtherGraph> others, VarGraph.Edge edge) {
        final Collection<VarGraph> otherGraphs = new ArrayList<>();
        for (final OtherGraph other : others) {
            final VarGraph otherGraph = other.graph();
            if (other.isTransitive()) {
                if (forwardBfsSearch(otherGraph, edge, ingoingMap)) {
                    // We found a cycle
                    final List<BooleanFormula> conflict = computeCycleReason(edge, ingoingMap);
                    trackReason(conflict);
                    getBackend().propagateConflict(conflict.toArray(new BooleanFormula[0]));
                    raisedConflict = true;
                } else if (enableTheoryPropagation) {
                    propagateConsequences(other.graph.propagateOther(edge));
                }
            } else {
                otherGraphs.add(otherGraph);
            }
        }
        // TODO(René): does this have to run every time?  `edge` is not always from a graph that is supposed to be acyclic.
        if (forwardBfsSearch(graph, otherGraphs, edge, ingoingMap)) {
            // We found a cycle
            final List<BooleanFormula> conflict = computeCycleReason(edge, ingoingMap);
            trackReason(conflict);
            getBackend().propagateConflict(conflict.toArray(new BooleanFormula[0]));
            raisedConflict = true;
        } else if (enableTheoryPropagation) {
            propagateConsequences(graph.propagate(edge, otherGraphs));
        }
    }

    private void propagateConsequences(Map<VarGraph.Edge, List<VarGraph.Edge>> approachingCycles) {
        for (Map.Entry<VarGraph.Edge, List<VarGraph.Edge>> cycle : approachingCycles.entrySet()) {
            final BooleanFormula consequence = cycle.getKey().getNegEdgeFormula();
            final var premise = new ArrayList<BooleanFormula>();
            cycle.getValue().forEach(x -> { assert x.isTrue(); });
            cycle.getValue().forEach(x -> addToReason(premise, x));
            getBackend().propagateConsequence(premise.toArray(new BooleanFormula[0]), consequence);
        }
    }

    private List<BooleanFormula> computeCycleReason(VarGraph.Edge edge, AlmostAcyclicityReason[] ingoingMap) {
        // Collect reason backwards
        final List<BooleanFormula> conflict = new ArrayList<>();
        int cur = edge.getSource();
        AlmostAcyclicityReason reasonEdge;
        AlmostAcyclicityEdge curEdge;
        VarGraph.Edge graphEdge;
        boolean otherEdgeSeen = false;
        do {
            reasonEdge = ingoingMap[cur];
            curEdge = otherEdgeSeen ? reasonEdge.unsatisfiedEdge() : reasonEdge.satisfiedEdge();
            if (!curEdge.isTransitive()) {
                otherEdgeSeen = true;
            }
            graphEdge = curEdge.edge();
            addToReason(conflict, graphEdge);
            cur = graphEdge.getSource();
        } while (graphEdge != edge);

        return conflict;
    }

    // ==========================================================================


    private boolean forwardBfsSearch(VarGraph transitiveGraph, VarGraph.Edge addedEdge, AlmostAcyclicityReason[] ingoingMap) {
        Arrays.fill(ingoingMap, null);
        workqueue.clear();
        workqueue.add(new AlmostAcyclicityNode(addedEdge.getTarget(), false));

        final int target = addedEdge.getSource();
        ingoingMap[addedEdge.getTarget()] = new AlmostAcyclicityReason(new AlmostAcyclicityEdge(addedEdge, false), null);

        do {
            // Forward BFS
            for (VarGraph.Edge outEdge : transitiveGraph.getTrueOutEdges(workqueue.poll().id())) {
                final int next = outEdge.getTarget();
                if (next == target) {
                    // Found cycle
                    ingoingMap[next] = new AlmostAcyclicityReason(new AlmostAcyclicityEdge(outEdge, true), null);
                    return true;
                } else if (ingoingMap[next] == null) {
                    ingoingMap[next] = new AlmostAcyclicityReason(new AlmostAcyclicityEdge(outEdge, true), null);
                    workqueue.add(new AlmostAcyclicityNode(next, false));
                }
            }
        } while (!workqueue.isEmpty());

        // No cycle found
        return false;
    }


    private boolean forwardBfsSearch(VarGraph transitiveGraph, Collection<VarGraph> otherGraphs, VarGraph.Edge addedEdge, AlmostAcyclicityReason[] ingoingMap) {
        Arrays.fill(ingoingMap, null);
        workqueue.clear();
        workqueue.add(new AlmostAcyclicityNode(addedEdge.getTarget(), true));

        final int target = addedEdge.getSource();
        ingoingMap[addedEdge.getTarget()] = new AlmostAcyclicityReason(null, new AlmostAcyclicityEdge(addedEdge, true));

        do {
            // Forward BFS
            final AlmostAcyclicityNode node = workqueue.poll();
            final int id = node.id();
            final boolean needsOtherEdge = node.needsOtherEdge();
            if (needsOtherEdge) {
                for (final VarGraph otherGraph : otherGraphs) {
                    for (VarGraph.Edge outEdge : otherGraph.getTrueOutEdges(id)) {
                        final int next = outEdge.getTarget();
                        if (next == target) {
                            // Found cycle
                            ingoingMap[next] = new AlmostAcyclicityReason(new AlmostAcyclicityEdge(outEdge, false), null);
                            return true;
                        } else{
                            final AlmostAcyclicityReason oldInEdge = ingoingMap[next];
                            if (oldInEdge == null) {
                                ingoingMap[next] = new AlmostAcyclicityReason(new AlmostAcyclicityEdge(outEdge, false), null);
                                workqueue.add(new AlmostAcyclicityNode(next, false));
                            } else if (oldInEdge.satisfiedEdge() == null) {
                                ingoingMap[next] = new AlmostAcyclicityReason(new AlmostAcyclicityEdge(outEdge, false), oldInEdge.unsatisfiedEdge());
                                workqueue.add(new AlmostAcyclicityNode(next, false));
                            }
                        }
                    }
                }
            }
            for (VarGraph.Edge outEdge : transitiveGraph.getTrueOutEdges(id)) {
                final int next = outEdge.getTarget();
                if (next == target && !needsOtherEdge) {
                    // Found cycle
                    ingoingMap[next] = new AlmostAcyclicityReason(new AlmostAcyclicityEdge(outEdge, true), null);
                    return true;
                } else {
                    final AlmostAcyclicityReason oldInEdge = ingoingMap[next];
                    if (oldInEdge == null) {
                        final AlmostAcyclicityEdge edge = new AlmostAcyclicityEdge(outEdge, true);
                        ingoingMap[next] = new AlmostAcyclicityReason(needsOtherEdge ? null : edge, needsOtherEdge ? edge : null);
                        workqueue.add(new AlmostAcyclicityNode(next, needsOtherEdge));
                    } else if (!needsOtherEdge && oldInEdge.satisfiedEdge() == null) {
                        ingoingMap[next] = new AlmostAcyclicityReason(new AlmostAcyclicityEdge(outEdge, true), oldInEdge.unsatisfiedEdge());
                        workqueue.add(new AlmostAcyclicityNode(next, false));
                    }
                }
            }
        } while (!workqueue.isEmpty());

        // No cycle found
        return false;
    }

    // ------------------------------------------------------------------------------------
    // Theory propagation

    private void backwardBfsPropagate(VarGraph graph, VarGraph.Edge addedEdge, AlmostAcyclicityReason[] ingoingMap) {
        Arrays.fill(outgoingMap, null);
        workqueue.clear();
        workqueue.add(new AlmostAcyclicityNode(addedEdge.getSource(), false));

        // Do backward BFS to collect disabled edges
        final List<AlmostAcyclicityBridge> disabledEdgesToPropagate = new ArrayList<>();
        do {
            for (VarGraph.Edge inEdge : graph.getInEdges(workqueue.poll().id())) {
                if (inEdge.isFalse()) {
                    continue;
                }

                final int next = inEdge.getSource();
                if (ingoingMap[next] != null && (allowDuplicatePropagation || !alreadyPropagatedEdges.contains(inEdge))) {
                    assert inEdge.isUnassigned();
                    disabledEdgesToPropagate.add(new AlmostAcyclicityBridge(new AlmostAcyclicityEdge(inEdge, true), true));
                    /*if ( !alreadyPropagatedEdges.contains(inEdge)) {
                        System.out.println("New prop reason for: " + inEdge);
                    }*/
                } else if (inEdge.isTrue() && outgoingMap[next] == null) {
                    outgoingMap[next] = new AlmostAcyclicityReason(null, new AlmostAcyclicityEdge(inEdge, true));
                    workqueue.add(new AlmostAcyclicityNode(next, false));
                }
            }
        } while (!workqueue.isEmpty());

        // Propagate disabled edges
        propagateDisabledEdges(disabledEdgesToPropagate, ingoingMap, outgoingMap);
    }

    private void backwardBfsPropagate(VarGraph graph, Collection<VarGraph> otherGraphs, VarGraph.Edge addedEdge, AlmostAcyclicityReason[] ingoingMap) {
        Arrays.fill(outgoingMap, null);
        workqueue.clear();
        workqueue.add(new AlmostAcyclicityNode(addedEdge.getSource(), true));

        // Do backward BFS to collect disabled edges
        final List<AlmostAcyclicityBridge> disabledEdgesToPropagate = new ArrayList<>();
        do {
            final AlmostAcyclicityNode node = workqueue.poll();
            final int id = node.id();
            final boolean needsOtherEdge = node.needsOtherEdge();
            if (needsOtherEdge) {
                for (final VarGraph otherGraph : otherGraphs) {
                    for (VarGraph.Edge inEdge : otherGraph.getInEdges(id)) {
                        if (inEdge.isFalse()) {
                            continue;
                        }

                        final int next = inEdge.getSource();
                        final AlmostAcyclicityReason nextInEdge = ingoingMap[next];
                        if (nextInEdge != null && nextInEdge.unsatisfiedEdge() != null && (allowDuplicatePropagation || !alreadyPropagatedEdges.contains(inEdge))) {
                            assert inEdge.isUnassigned();
                            disabledEdgesToPropagate.add(new AlmostAcyclicityBridge(new AlmostAcyclicityEdge(inEdge, false), false));
                            /*if ( !alreadyPropagatedEdges.contains(inEdge)) {
                                System.out.println("New prop reason for: " + inEdge);
                            }*/
                        } else if (inEdge.isTrue()) {
                            final AlmostAcyclicityReason oldOutEdge = outgoingMap[next];
                            if (oldOutEdge == null) {
                                outgoingMap[next] = new AlmostAcyclicityReason(new AlmostAcyclicityEdge(inEdge, false), null);
                                workqueue.add(new AlmostAcyclicityNode(next, false));
                            } else if (oldOutEdge.satisfiedEdge() == null) {
                                outgoingMap[next] = new AlmostAcyclicityReason(new AlmostAcyclicityEdge(inEdge, false), oldOutEdge.unsatisfiedEdge());
                                workqueue.add(new AlmostAcyclicityNode(next, false));
                            }
                        }
                    }
                }
            }
            for (VarGraph.Edge inEdge : graph.getInEdges(id)) {
                if (inEdge.isFalse()) {
                    continue;
                }

                final int next = inEdge.getSource();
                final AlmostAcyclicityReason nextInEdge = ingoingMap[next];
                if (nextInEdge != null && (needsOtherEdge ? nextInEdge.satisfiedEdge() : nextInEdge.unsatisfiedEdge()) != null
                        && (allowDuplicatePropagation || !alreadyPropagatedEdges.contains(inEdge))) {
                    assert inEdge.isUnassigned();
                    disabledEdgesToPropagate.add(new AlmostAcyclicityBridge(new AlmostAcyclicityEdge(inEdge, true), needsOtherEdge));
                    /*if ( !alreadyPropagatedEdges.contains(inEdge)) {
                        System.out.println("New prop reason for: " + inEdge);
                    }*/
                } else if (inEdge.isTrue()) {
                    final AlmostAcyclicityReason oldOutEdge = outgoingMap[next];
                    if (oldOutEdge == null) {
                        final AlmostAcyclicityEdge edge = new AlmostAcyclicityEdge(inEdge, true);
                        outgoingMap[next] = new AlmostAcyclicityReason(needsOtherEdge ? null : edge, needsOtherEdge ? edge : null);
                        workqueue.add(new AlmostAcyclicityNode(next, needsOtherEdge));
                    } else if (!needsOtherEdge && oldOutEdge.satisfiedEdge() == null) {
                        outgoingMap[next] = new AlmostAcyclicityReason(new AlmostAcyclicityEdge(inEdge, true), oldOutEdge.unsatisfiedEdge());
                        workqueue.add(new AlmostAcyclicityNode(next, false));
                    }
                }
            }
        } while (!workqueue.isEmpty());

        // Propagate disabled edges
        propagateDisabledEdges(disabledEdgesToPropagate, ingoingMap, outgoingMap);
    }

    private void propagateDisabledEdges(List<AlmostAcyclicityBridge> disabledEdgesToPropagate, AlmostAcyclicityReason[] ingoingMap, AlmostAcyclicityReason[] outgoingMap) {
        AlmostAcyclicityEdge edge;
        VarGraph.Edge graphEdge;
        boolean isTransitive;
        boolean isOutgoingTransitive;
        for (var bridge : disabledEdgesToPropagate) {
            edge = bridge.edge();
            graphEdge = edge.edge();
            isTransitive = edge.isTransitive();
            isOutgoingTransitive = bridge.isOutgoingTransitive();
            assert graphEdge.isUnassigned();
            final List<BooleanFormula> reason = new ArrayList<>();

            // Collect reason backwards
            boolean isStart = true;
            final int start = graphEdge.getSource();
            int cur = start;
            AlmostAcyclicityReason reasonEdge;
            AlmostAcyclicityEdge curEdge;
            VarGraph.Edge curGraphEdge;
            boolean otherEdgeSeen = !isOutgoingTransitive;
            while ((reasonEdge = ingoingMap[cur]) != null && (isTransitive || isStart || cur != start)) {
                curEdge = otherEdgeSeen ? reasonEdge.unsatisfiedEdge() : reasonEdge.satisfiedEdge();
                if (!curEdge.isTransitive()) {
                    otherEdgeSeen = true;
                }
                curGraphEdge = curEdge.edge();
                addToReason(reason, curGraphEdge);
                cur = curGraphEdge.getSource();
                isStart = false;
            }

            final int target = cur;

            // Collect reason forwards
            cur = graphEdge.getTarget();
            otherEdgeSeen = isOutgoingTransitive || !isTransitive;
            while (cur != target) {
                reasonEdge = outgoingMap[cur];
                curEdge = otherEdgeSeen ? reasonEdge.unsatisfiedEdge() : reasonEdge.satisfiedEdge();
                if (!curEdge.isTransitive()) {
                    otherEdgeSeen = true;
                }
                curGraphEdge = curEdge.edge();
                addToReason(reason, curGraphEdge);
                cur = curGraphEdge.getTarget();
            }

            // Propagate
            assert !reason.isEmpty();
            final BooleanFormula[] propReason = reason.toArray(new BooleanFormula[0]);
            getBackend().propagateConsequence(propReason, graphEdge.getNegEdgeFormula());
            numPropagations++;
            alreadyPropagatedEdges.add(graphEdge);
        }
    }

    private void addToReason(List<BooleanFormula> reason, VarGraph.Edge edge) {
        if (edge.isMust()) {
            final EdgeExecFormula edgeExec = mustEdgeToExec.get(edge);
            reason.add(edgeExec.exec1());
            final BooleanFormula exec2 = edgeExec.exec2();
            if (exec2 != null) {
                reason.add(exec2);
            }
        } else {
            reason.add(edge.getEdgeVar());
        }
    }

    // ---------------------------------------- Statistics ----------------------------------------

    private void trackReason(List<BooleanFormula> reason) {
        observedReasons.compute(new HashSet<>(reason), (k, v) -> v == null ? 1 : v + 1);
    }

    public void printStatistics() {
        int uniqueReasons = observedReasons.size();
        int totalReasons = observedReasons.values().stream().mapToInt(v -> v).sum();
        int maxDuplicate = observedReasons.values().stream().mapToInt(v -> v).max().orElse(0);
        System.out.println("total: " + totalReasons + " ### unique: " + uniqueReasons + " ### maxDup: " + maxDuplicate);
        System.out.println("numPropagations: " + numPropagations);
    }


    // ===================================== Helper classes ====================================

    // We use a "case" per irreflexivity axiom we want to track
    private record Case(Relation transitive, Relation other, VarGraph transitiveGraph, VarGraph otherGraph) { }

    private record FormulaData(VarGraph graph, VarGraph.Edge edge, Collection<OtherGraph> other) { }

    private record OtherGraph(VarGraph graph, boolean isTransitive) { }

    private record PartiallyResolvedMustEdge(BooleanFormula mustEdge, ExecGraph.ExecLiteral remainingExec) { }

    private record EdgeExecFormula(BooleanFormula exec1, BooleanFormula exec2) { }

    private record AlmostAcyclicityNode(int id, boolean needsOtherEdge) { }

    private record AlmostAcyclicityEdge(VarGraph.Edge edge, boolean isTransitive) { }

    private record AlmostAcyclicityBridge(AlmostAcyclicityEdge edge, boolean isOutgoingTransitive) { }

    private record AlmostAcyclicityReason(AlmostAcyclicityEdge satisfiedEdge, AlmostAcyclicityEdge unsatisfiedEdge) { }

    // TODO: Test code to minimize hashtable lookup times with BooleanFormula
    //  The IdentityHashMap is used to avoid expensive .equals calls on BooleanFormula
    //  We need to populate the map in onKnownValue because only there we get
    //  canonical instances for BooleanFormula that can be compared by identity.
    private static class CachingFormulaMap<TData> {
        private final IdentityHashMap<BooleanFormula, TData> formulaLookup;
        private final Function<BooleanFormula, TData> dataConstructor;

        public CachingFormulaMap(int expectedMaxSize, Map<BooleanFormula, TData> initialData,
                                 Function<BooleanFormula, TData> dataConstructor) {
            this.formulaLookup = new IdentityHashMap<>(expectedMaxSize);
            this.formulaLookup.putAll(initialData);
            this.dataConstructor = dataConstructor;
        }

        public TData get(BooleanFormula formula) {
            return formulaLookup.computeIfAbsent(formula, dataConstructor);
        }
    }
}