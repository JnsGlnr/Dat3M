package com.dat3m.dartagnan.solver.propagators;

import com.dat3m.dartagnan.encoding.EncodingContext;
import com.dat3m.dartagnan.encoding.WmmEncoder;
import com.dat3m.dartagnan.program.analysis.EventDomainRepository;
import com.dat3m.dartagnan.program.event.Event;
import com.dat3m.dartagnan.utils.collections.IndexedDomain;
import com.dat3m.dartagnan.wmm.Relation;
import com.dat3m.dartagnan.wmm.analysis.RelationAnalysis;
import com.dat3m.dartagnan.wmm.axiom.Irreflexivity;
import com.dat3m.dartagnan.wmm.definition.Composition;
import com.dat3m.dartagnan.wmm.definition.Inverse;
import com.dat3m.dartagnan.wmm.utils.graph.EventGraph;
import org.sosy_lab.java_smt.api.BooleanFormula;
import org.sosy_lab.java_smt.api.BooleanFormulaManager;
import org.sosy_lab.java_smt.api.PropagatorBackend;
import org.sosy_lab.java_smt.basicimpl.AbstractUserPropagator;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

public class IrreflexivityPropagator extends AbstractUserPropagator {

    private static final boolean enableTheoryPropagation = true;
    // Might weaken propagation/learning if set to true?
    private static final boolean stopOnConflict = false;
    // If set to true, we can propagate the same edge twice but with different reasons
    private static final boolean allowDuplicatePropagation = false;

    private final RelationAnalysis relationAnalysis;
    private final EncodingContext context;
    private final WmmEncoder wmmEncoder;
    private final List<Case> cases = new ArrayList<>();
    private final Map<BooleanFormula, PatternGraph> lit2PatternEdges = new HashMap<>();
    private final IndexedDomain<Event> domain;
    private final BooleanFormulaManager bmgr;

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


    public IrreflexivityPropagator(WmmEncoder wmmEncoder, EncodingContext ctx) {
        this.context = ctx;
        this.relationAnalysis = ctx.getAnalysisContext().requires(RelationAnalysis.class);
        this.wmmEncoder = wmmEncoder;

        this.domain = ctx.getAnalysisContext().requires(EventDomainRepository.class)
                .getDomain(EventDomainRepository.DomainBound.VISIBLE);
        this.bmgr = context.getBooleanFormulaManager();
    }

    public void registerAxiom(Irreflexivity axiom) {
        if (cases.stream().anyMatch(c -> c.axiom() == axiom)) {
            return;
        }
        final Pattern pattern = new Pattern();
        final Map<Relation, Pattern.Edge> patternEdges = new LinkedHashMap<>(4);
        if (axiom.getRelation().getDefinition() instanceof Composition comp) {
            Relation leftRel = comp.getLeftOperand();
            boolean isInverse = false;
            if (leftRel.getDefinition() instanceof Inverse inverse) {
                isInverse = true;
                leftRel = inverse.getOperand();
            }
            final VarGraph leftGraph = new VarGraph(leftRel, domain.size(), context.getBooleanFormulaManager());
            patternEdges.put(leftRel, pattern.appendPatternEdge(leftGraph, isInverse));

            Relation rightRel = comp.getRightOperand();
            isInverse = false;
            if (rightRel.getDefinition() instanceof Inverse inverse) {
                isInverse = true;
                rightRel = inverse.getOperand();
            }
            final VarGraph rightGraph = new VarGraph(rightRel, domain.size(), context.getBooleanFormulaManager());
            patternEdges.put(rightRel, pattern.appendLastPatternEdge(rightGraph, isInverse));

            pattern.build();
            final Case c = new Case(axiom, pattern, patternEdges);
            cases.add(c);
        }
    }

    @Override
    public void initializeWithBackend(PropagatorBackend backend) {
        super.initializeWithBackend(backend);

        backend.notifyOnKnownValue();

        AtomicInteger numDynamicEdges = new AtomicInteger();
        final Map<Relation, PatternGraph> relToEdges = new HashMap<>();
        for (Case c : cases) {
            final Pattern pattern = c.pattern();
            for (final Map.Entry<Relation, Pattern.Edge> subGraph : c.subGraphs().entrySet()) {
                final Relation rel = subGraph.getKey();
                final EventGraph must = relationAnalysis.getKnowledge(rel).getMustSet();
                final EventGraph activeSet = wmmEncoder.getActiveSet(rel.getDefinition());
                final Pattern.Edge patternEdge = subGraph.getValue();
                final VarGraph graph = patternEdge.graph();

                final PatternGraph patternGraph = relToEdges.get(rel);
                if (patternGraph == null) {
                    final List<Pattern.Edge> newPatternEdges = new ArrayList<>();
                    newPatternEdges.add(patternEdge);
                    final Map<Pattern, List<Pattern.Edge>> patternEdges = new HashMap<>();
                    patternEdges.put(pattern, newPatternEdges);
                    final PatternGraph newPatternGraph = new PatternGraph(graph, patternEdges);
                    activeSet.apply((x, y) -> {
                        final int idx = domain.indexOf(x);
                        final int idy = domain.indexOf(y);
                        if (must.contains(x, y)) {
                            // TODO: This is unsound unless the must-edges satisfy
                            //  (x,y) in must(r) /\ (y, z) in must(r) => (x, z) in must(r^+)
                            //  which is the case for SC
                            graph.addMustEdge(idx, idy);
                        } else {
                            final BooleanFormula edgeLit = context.edge(rel, x, y);
                            lit2PatternEdges.put(edgeLit, newPatternGraph);
                            graph.addVarEdge(idx, idy, edgeLit);
                            backend.registerExpression(edgeLit);
                            numDynamicEdges.getAndIncrement();
                        }
                    });
                    relToEdges.put(rel, newPatternGraph);
                } else {
                    final Map<Pattern, List<Pattern.Edge>> patternEdges = patternGraph.patternEdges();
                    final List<Pattern.Edge> curPatternEdges = patternEdges.get(pattern);
                    if (curPatternEdges == null) {
                        final List<Pattern.Edge> newPatternEdges = new ArrayList<>();
                        newPatternEdges.add(patternEdge);
                        patternEdges.put(pattern, newPatternEdges);
                    } else {
                        curPatternEdges.add(patternEdge);
                    }
                }
            }
        }

        formulaLookup = new CachingFormulaMap<>(numDynamicEdges.get() * 2, key -> {
            final PatternGraph patternGraph = lit2PatternEdges.get(key);
            final VarGraph.Edge edge = patternGraph.graph().getEdge(key);
            return new FormulaData(patternGraph, edge);
        });
        alreadyPropagatedEdges = Collections.newSetFromMap(new IdentityHashMap<>(numDynamicEdges.get()));
    }

    @Override
    public void onPush() {
        curLevel++;
        cases.forEach(c -> {
            c.pattern().push();
            c.subGraphs().values().forEach(edge -> edge.graph().push());
        });
        // System.out.println("------- Push: " + curLevel + " -------");
    }

    @Override
    public void onPop(int numPoppedLevels) {
        raisedConflict = false;
        curLevel -= numPoppedLevels;
        cases.forEach(c -> {
            c.pattern().pop(numPoppedLevels);
            c.subGraphs().values().forEach(edge -> edge.graph().pop(numPoppedLevels));
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

        final FormulaData data = formulaLookup.get(expr);
        final PatternGraph patternGraph = data.patternGraph();
        final VarGraph graph = patternGraph.graph();
        final VarGraph.Edge edge = data.edge();
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
        } else {
            final int source = edge.getSource();
            final int target = edge.getTarget();
            for (final Map.Entry<Pattern, List<Pattern.Edge>> patternEdges : patternGraph.patternEdges().entrySet()) {
                final Pattern pattern = patternEdges.getKey();
                patternEdges.getValue().forEach(patternEdge -> pattern.removeEdge(patternEdge, source, target));
            }
        }
    }

    // Checks for cycles caused by adding <edge> and possibly raises a conflict.
    private void processEdgeAddition(Pattern pattern, List<Pattern.Edge> patternEdges, VarGraph.Edge edge) {
        final int source = edge.getSource();
        final int target = edge.getTarget();
        for (final Pattern.Edge patternEdge : patternEdges) {
            final Pattern.MatchingResult result = pattern.addEdge(patternEdge, source, target);
            for (final Integer[] match : result.matches()) {
                // We found a cycle
                final List<BooleanFormula> conflict = computeCycleReason(pattern, match);
                trackReason(conflict);
                getBackend().propagateConflict(conflict.toArray(new BooleanFormula[0]));
                raisedConflict = true;
            }
            if (enableTheoryPropagation) {
                for (final Integer[] match : result.theoryPropagations()) {
                    final PropagationResult prop = propagate(pattern, match);
                    final BooleanFormula[] propReason = prop.reason().toArray(new BooleanFormula[0]);
                    getBackend().propagateConsequence(propReason, bmgr.or(prop.consequence()));
                    numPropagations++;
                    alreadyPropagatedEdges.add(edge);
                }
            }
        }
    }

    private List<BooleanFormula> computeCycleReason(Pattern pattern, Integer[] match) {
        final List<BooleanFormula> conflictVars = new ArrayList<>();
        for (final Pattern.Edge patternEdge : pattern.edges) {
            final VarGraph graph = patternEdge.graph();
            final int source = match[patternEdge.from().id()];
            final int target = match[patternEdge.to().id()];
            final VarGraph.Edge edge = patternEdge.isInverse() ? graph.findEdge(target, source) : graph.findEdge(source, target);
            conflictVars.add(edge.getEdgeVar());
        }
        return conflictVars;
    }

    private PropagationResult propagate(Pattern pattern, Integer[] match) {
        final List<BooleanFormula> reason = new ArrayList<>();
        final List<BooleanFormula> consequence = new ArrayList<>();
        for (final Pattern.Edge patternEdge : pattern.edges) {
            final VarGraph graph = patternEdge.graph();
            final boolean isInversed = patternEdge.isInverse();
            final int source = isInversed ? match[patternEdge.to().id()] : match[patternEdge.from().id()];
            final int target = isInversed ? match[patternEdge.from().id()] : match[patternEdge.to().id()];
            if (graph.contains(source, target)) {
                reason.add(graph.findEdge(source, target).getEdgeVar());
            } else {
                if (graph.hasEdge(source, target)) {
                    consequence.add(graph.findEdge(source, target).getNegEdgeVar());
                }
            }
        }
        return new PropagationResult(reason, consequence);
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
    private record Case(Irreflexivity axiom, Pattern pattern, Map<Relation, Pattern.Edge> subGraphs) { }

    private record PatternGraph(VarGraph graph, Map<Pattern, List<Pattern.Edge>> patternEdges) { }

    private record FormulaData(PatternGraph patternGraph, VarGraph.Edge edge) { }

    private record PropagationResult(List<BooleanFormula> reason, List<BooleanFormula> consequence) { }


    // TODO: Test code to minimize hashtable lookup times with BooleanFormula
    //  The IdentityHashMap is used to avoid expensive .equals calls on BooleanFormula
    //  We need to populate the map in onKnownValue because only there we get
    //  canonical instances for BooleanFormula that can be compared by identity.
    private static class CachingFormulaMap<TData> {
        private final IdentityHashMap<BooleanFormula, TData> formulaLookup;
        private final Function<BooleanFormula, TData> dataConstructor;

        public CachingFormulaMap(int expectedMaxSize, Function<BooleanFormula, TData> dataConstructor) {
            this.formulaLookup = new IdentityHashMap<>(expectedMaxSize);
            this.dataConstructor = dataConstructor;
        }

        public TData get(BooleanFormula formula) {
            return formulaLookup.computeIfAbsent(formula, dataConstructor);
        }
    }

}