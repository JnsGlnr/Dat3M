package com.dat3m.dartagnan.solver.propagators;

import com.dat3m.dartagnan.encoding.EncodingContext;
import com.dat3m.dartagnan.encoding.WmmEncoder;
import com.dat3m.dartagnan.program.analysis.EventDomainRepository;
import com.dat3m.dartagnan.program.analysis.ExecutionAnalysis;
import com.dat3m.dartagnan.program.event.Event;
import com.dat3m.dartagnan.utils.collections.IndexedDomain;
import com.dat3m.dartagnan.utils.collections.IndexedSet;
import com.dat3m.dartagnan.wmm.Definition;
import com.dat3m.dartagnan.wmm.Relation;
import com.dat3m.dartagnan.wmm.analysis.RelationAnalysis;
import com.dat3m.dartagnan.wmm.axiom.Irreflexivity;
import com.dat3m.dartagnan.wmm.definition.Inverse;
import com.dat3m.dartagnan.wmm.definition.SetIdentity;
import com.dat3m.dartagnan.wmm.utils.graph.EventGraph;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.configuration.Option;
import org.sosy_lab.common.configuration.Options;
import org.sosy_lab.java_smt.api.BooleanFormula;
import org.sosy_lab.java_smt.api.BooleanFormulaManager;
import org.sosy_lab.java_smt.api.PropagatorBackend;
import org.sosy_lab.java_smt.basicimpl.AbstractUserPropagator;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static com.dat3m.dartagnan.configuration.OptionNames.PARTIAL_PATTERN_MATCHING;

@Options
public class IrreflexivityPropagator extends AbstractUserPropagator {

    @Option(name=PARTIAL_PATTERN_MATCHING,
            description="Maintain partial patterns during pattern matching",
            secure=true,
            toUppercase=true)
    private boolean usePartialPatternMatching = false;

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
    private final Map<Relation, VarGraph> relationGraphMap = new HashMap<>();
    private final Map<BooleanFormula, PatternGraph> lit2PatternEdges = new HashMap<>();
    private final IndexedDomain<Event> domain;
    private final BooleanFormulaManager bmgr;
    private final ExecGraph exec;
    private final Map<BooleanFormula, List<PartiallyResolvedMustEdge>> execToMustEdges;
    private final Map<VarGraph.Edge, EdgeExecFormula> mustEdgeToExec;

    // -------- Dynamic search data --------
    private int curLevel = 0;
    private boolean raisedConflict = false;

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


    private IrreflexivityPropagator(WmmEncoder wmmEncoder, EncodingContext ctx) {
        this.context = ctx;
        this.relationAnalysis = ctx.getAnalysisContext().requires(RelationAnalysis.class);
        this.executionAnalysis = ctx.getAnalysisContext().requires(ExecutionAnalysis.class);
        this.wmmEncoder = wmmEncoder;

        this.domain = ctx.getAnalysisContext().requires(EventDomainRepository.class)
                .getDomain(EventDomainRepository.DomainBound.VISIBLE);
        this.bmgr = context.getBooleanFormulaManager();
        this.exec = new ExecGraph(domain.size());
        execToMustEdges = new HashMap<>(domain.size() * 4 / 3);
        mustEdgeToExec = new HashMap<>();
    }

    public static IrreflexivityPropagator withContext(final EncodingContext context, final WmmEncoder wmmEncoder)
            throws InvalidConfigurationException {
        final IrreflexivityPropagator propagator = new IrreflexivityPropagator(wmmEncoder, context);
        context.getTask().getConfig().inject(propagator);
        return propagator;
    }

    public record AxiomWithComponents(Irreflexivity axiom, List<Definition> components) {
    }

    public void registerAxiom(AxiomWithComponents axiom) {
        if (cases.stream().anyMatch(c -> c.axiom() == axiom.axiom())) {
            return;
        }
        final List<Definition> components = axiom.components();
        if (components.size() == 1) {
            return;
        }
        final Pattern pattern = usePartialPatternMatching ? new PartiallyMatchablePattern(domain) : new SimplePattern(domain);
        final Map<Relation, List<Pattern.Edge>> patternEdges = new LinkedHashMap<>();
        final List<IndexedSet<Event>> curSets = new ArrayList<>();
        Relation lastRel = null;
        int i = 0;
        for (Definition definition : components) {
            Relation relation = definition.getDefinedRelation();
            boolean isInverse = false;
            if (definition instanceof Inverse inverse) {
                isInverse = true;
                relation = inverse.getOperand();
                definition = relation.getDefinition();
            }
            if (definition instanceof final SetIdentity setIdentity) {
                final Relation set = setIdentity.getDomain();
                final RelationAnalysis.Knowledge k = relationAnalysis.getKnowledge(set);
                final EventGraph must = k.getMustSet();
                if (k.getMaySet().size() == must.size()) {
                    curSets.add((IndexedSet<Event>) must.getDomain());
                    ++i;
                    continue;
                }
            }
            final VarGraph graph = relationGraphMap.computeIfAbsent(relation, rel -> new VarGraph(rel, domain.size(), context.getBooleanFormulaManager()));
            final Pattern.Edge patternEdge = i++ == components.size() - 1 ? pattern.appendLastPatternEdge(graph, isInverse, curSets) : pattern.appendPatternEdge(graph, isInverse, curSets);
            patternEdges.computeIfAbsent(relation, k -> new ArrayList<>()).add(patternEdge);
            curSets.clear();
            lastRel = relation;
        }
        if (!curSets.isEmpty()) {
            final Pattern.Edge outdatedEdge = pattern.closeBuild(curSets);
            final Pattern.Edge closingEdge = pattern.edges.getLast();
            patternEdges.getOrDefault(lastRel, new ArrayList<>()).replaceAll(edge -> edge == outdatedEdge ? closingEdge : edge);
        }
        pattern.build();
        final Case c = new Case(axiom.axiom(), pattern, patternEdges);
        cases.add(c);
    }

    @Override
    public void initializeWithBackend(PropagatorBackend backend) {
        super.initializeWithBackend(backend);

        backend.notifyOnKnownValue();

        AtomicInteger numDynamicEdges = new AtomicInteger();
        final Map<BooleanFormula, FormulaData> mustFormulas = new HashMap<>();
        final Map<Relation, PatternGraph> relToEdges = new HashMap<>();
        for (Case c : cases) {
            final Pattern pattern = c.pattern();
            for (final Map.Entry<Relation, List<Pattern.Edge>> subGraph : c.subGraphs().entrySet()) {
                final Relation rel = subGraph.getKey();
                final EventGraph must = relationAnalysis.getKnowledge(rel).getMustSet();
                final EventGraph activeSet = wmmEncoder.getActiveSet(rel.getDefinition());
                final List<Pattern.Edge> patternEdges = subGraph.getValue();
                final VarGraph graph = relationGraphMap.get(rel);

                final PatternGraph patternGraph = relToEdges.get(rel);
                if (patternGraph == null) {
                    final Map<Pattern, List<Pattern.Edge>> patternEdgesForPattern = new HashMap<>();
                    patternEdgesForPattern.put(pattern, new ArrayList<>(patternEdges));
                    final PatternGraph newPatternGraph = new PatternGraph(graph, patternEdgesForPattern);
                    EventGraph.union(activeSet, must).apply((x, y) -> {
                        final int idx = domain.indexOf(x);
                        final int idy = domain.indexOf(y);
                        final BooleanFormula edgeLit = context.edgeVariable(rel.getNameOrTerm(), x, y);
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
                            VarGraph.Edge edge = graph.addMustEdge(idx, idy, exec);
                            mustEdgeToExec.put(edge, edgeExec);
                            mustFormulas.put(edgeLit, new FormulaData(newPatternGraph, edge));
                        } else {
                            lit2PatternEdges.put(edgeLit, newPatternGraph);
                            graph.addVarEdge(idx, idy, edgeLit);
                            backend.registerExpression(edgeLit);
                        }
                        numDynamicEdges.getAndIncrement();
                    });
                    relToEdges.put(rel, newPatternGraph);
                } else {
                    final Map<Pattern, List<Pattern.Edge>> allPatternEdges = patternGraph.patternEdges();
                    final List<Pattern.Edge> curPatternEdges = allPatternEdges.get(pattern);
                    if (curPatternEdges == null) {
                        allPatternEdges.put(pattern, new ArrayList<>(patternEdges));
                    } else {
                        curPatternEdges.addAll(patternEdges);
                    }
                }
            }
        }
        if (usePartialPatternMatching) {
            for (final PatternGraph patternGraph : relToEdges.values()) {
                final VarGraph graph = patternGraph.graph();
                for (final Map.Entry<Pattern, List<Pattern.Edge>> patternEdges : patternGraph.patternEdges().entrySet()) {
                    final Pattern pattern = patternEdges.getKey();
                    for (final Pattern.Edge edge : patternEdges.getValue()) {
                        for (final VarGraph.Edge graphEdge : graph.allEdges) {
                            if (graphEdge.isMust()) {
                                pattern.addEdge(edge, graphEdge.getSource(), graphEdge.getTarget());
                            }
                        }
                    }
                }
            }
        }

        formulaLookup = new CachingFormulaMap<>(numDynamicEdges.get() * 2, mustFormulas, key -> {
            final PatternGraph patternGraph = lit2PatternEdges.get(key);
            final VarGraph.Edge edge = patternGraph.graph().getEdge(key);
            return new FormulaData(patternGraph, edge);
        });
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
            c.pattern().push();
            c.subGraphs().keySet().forEach(rel -> relationGraphMap.get(rel).push());
        });
        // System.out.println("------- Push: " + curLevel + " -------");
    }

    @Override
    public void onPop(int numPoppedLevels) {
        raisedConflict = false;
        curLevel -= numPoppedLevels;
        exec.pop(numPoppedLevels);
        cases.forEach(c -> {
            c.pattern().pop(numPoppedLevels);
            c.subGraphs().keySet().forEach(rel -> relationGraphMap.get(rel).pop(numPoppedLevels));
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
        final PatternGraph patternGraph = data.patternGraph();
        final VarGraph graph = patternGraph.graph();
        final VarGraph.Edge edge = data.edge();

        if (edge.isUnassigned() || value != edge.isTrue()) {
            graph.assignEdge(edge, value);

            if (value) {
                if (alreadyPropagatedEdges.contains(edge)) {
                    raisedConflict = true;
                    // System.out.println("Propagation conflict");
                    return;
                }
                for (final Map.Entry<Pattern, List<Pattern.Edge>> patternEdges : patternGraph.patternEdges().entrySet()) {
                    processEdgeAddition(patternEdges.getKey(), patternEdges.getValue(), edge);
                }

                numChecks++;
                if (numChecks % 1000000 == 0) {
                    System.out.println("numChecks: " + numChecks);
                    printStatistics();
                }
            } else if (usePartialPatternMatching) {
                final int source = edge.getSource();
                final int target = edge.getTarget();
                for (final Map.Entry<Pattern, List<Pattern.Edge>> patternEdges : patternGraph.patternEdges().entrySet()) {
                    final Pattern pattern = patternEdges.getKey();
                    patternEdges.getValue().forEach(patternEdge -> pattern.removeEdge(patternEdge, source, target));
                }
            }
        }
    }

    // Checks for cycles caused by adding <edge> and possibly raises a conflict.
    private void processEdgeAddition(Pattern pattern, List<Pattern.Edge> patternEdges, VarGraph.Edge edge) {
        final int source = edge.getSource();
        final int target = edge.getTarget();
        for (final Pattern.Edge patternEdge : patternEdges) {
            final Pattern.MatchingResult result = pattern.addEdge(patternEdge, source, target);
            for (final int[] match : result.matches()) {
                // We found a cycle
                final List<BooleanFormula> conflict = computeCycleReason(pattern, match);
                trackReason(conflict);
                getBackend().propagateConflict(conflict.toArray(new BooleanFormula[0]));
                raisedConflict = true;
            }
            if (enableTheoryPropagation) {
                for (final int[] match : result.theoryPropagations()) {
                    final PropagationResult prop = propagate(pattern, match);
                    if (prop != null) {
                        final BooleanFormula[] propReason = prop.reason().toArray(new BooleanFormula[0]);
                        getBackend().propagateConsequence(propReason, bmgr.or(prop.consequence()));
                        numPropagations++;
                    }
                }
            }
        }
    }

    private List<BooleanFormula> computeCycleReason(Pattern pattern, int[] match) {
        final List<BooleanFormula> conflictVars = new ArrayList<>();
        for (final Pattern.Edge patternEdge : pattern.edges) {
            final VarGraph graph = patternEdge.graph();
            final int source = match[patternEdge.from().id()];
            final int target = match[patternEdge.to().id()];
            final VarGraph.Edge edge = graph.getEdge(source, target);
            addToReason(conflictVars, edge);
        }
        return conflictVars;
    }

    private PropagationResult propagate(Pattern pattern, int[] match) {
        final List<BooleanFormula> reason = new ArrayList<>();
        final List<BooleanFormula> consequence = new ArrayList<>();
        final List<VarGraph.Edge> propagatedEdges = new ArrayList<>();
        for (final Pattern.Edge patternEdge : pattern.edges) {
            final VarGraph graph = patternEdge.graph();
            final Pattern.Node fromNode = patternEdge.from();
            final Pattern.Node toNode = patternEdge.to();
            final int source = match[fromNode.id()];
            final int target = match[toNode.id()];
            final VarGraph.Edge edge = graph.containsWithEdge(source, target);
            if (edge != null) {
                addToReason(reason, edge);
            } else {
                final VarGraph.Edge propEdge = graph.edgeMatrix[source][target];
                if (propEdge != null && propEdge.isUnassigned() && fromNode.check(source) && toNode.check(target)) {
                    consequence.add(propEdge.getNegEdgeFormula());
                    propagatedEdges.add(propEdge);
                }
            }
        }
        if (consequence.isEmpty()) {
            return null;
        }
        if (propagatedEdges.size() == 1) {
            final VarGraph.Edge propagatedEdge = propagatedEdges.getFirst();
            if (allowDuplicatePropagation || !alreadyPropagatedEdges.contains(propagatedEdge)) {
                alreadyPropagatedEdges.add(propagatedEdge);
            } else {
                return null;
            }
        }
        return new PropagationResult(reason, consequence);
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
    private record Case(Irreflexivity axiom, Pattern pattern, Map<Relation, List<Pattern.Edge>> subGraphs) { }

    private record PatternGraph(VarGraph graph, Map<Pattern, List<Pattern.Edge>> patternEdges) { }

    private record FormulaData(PatternGraph patternGraph, VarGraph.Edge edge) { }

    private record PropagationResult(List<BooleanFormula> reason, List<BooleanFormula> consequence) { }

    private record PartiallyResolvedMustEdge(BooleanFormula mustEdge, ExecGraph.ExecLiteral remainingExec) { }

    private record EdgeExecFormula(BooleanFormula exec1, BooleanFormula exec2) { }


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