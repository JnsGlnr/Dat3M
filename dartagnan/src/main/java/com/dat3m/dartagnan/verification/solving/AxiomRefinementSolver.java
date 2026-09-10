package com.dat3m.dartagnan.verification.solving;

import com.dat3m.dartagnan.configuration.Method;
import com.dat3m.dartagnan.configuration.Property;
import com.dat3m.dartagnan.encoding.*;
import com.dat3m.dartagnan.program.Program;
import com.dat3m.dartagnan.program.event.Event;
import com.dat3m.dartagnan.smt.ProverWithTracker;
import com.dat3m.dartagnan.solver.caat.CAATSolver;
import com.dat3m.dartagnan.solver.caat4wmm.EazyRefiner;
import com.dat3m.dartagnan.solver.caat4wmm.EazyWMMSolver;
import com.dat3m.dartagnan.solver.caat4wmm.coreReasoning.CoreImplication;
import com.dat3m.dartagnan.solver.caat4wmm.coreReasoning.CoreLiteral;
import com.dat3m.dartagnan.solver.caat4wmm.coreReasoning.TrivialImplications;
import com.dat3m.dartagnan.utils.logic.Conjunction;
import com.dat3m.dartagnan.utils.logic.DNF;
import com.dat3m.dartagnan.verification.Context;
import com.dat3m.dartagnan.verification.Task;
import com.dat3m.dartagnan.verification.VerificationTask;
import com.dat3m.dartagnan.verification.model.EventData;
import com.dat3m.dartagnan.wmm.Constraint;
import com.dat3m.dartagnan.wmm.Definition;
import com.dat3m.dartagnan.wmm.Relation;
import com.dat3m.dartagnan.wmm.Wmm;
import com.dat3m.dartagnan.wmm.analysis.RelationAnalysis;
import com.dat3m.dartagnan.wmm.axiom.Acyclicity;
import com.dat3m.dartagnan.wmm.axiom.Axiom;
import com.dat3m.dartagnan.wmm.axiom.Irreflexivity;
import com.dat3m.dartagnan.wmm.definition.*;
import com.dat3m.dartagnan.wmm.processing.EmptinessToIrreflexivity;
import com.dat3m.dartagnan.wmm.processing.MergeIrreflexivities;
import com.dat3m.dartagnan.wmm.processing.RemoveDeadRelations;
import com.dat3m.dartagnan.wmm.processing.SimplifyIrreflexivities;
import com.dat3m.dartagnan.wmm.utils.graph.EventGraph;
import com.dat3m.dartagnan.wmm.utils.graph.mutable.MapEventGraph;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.configuration.Option;
import org.sosy_lab.common.configuration.Options;
import org.sosy_lab.java_smt.api.BooleanFormula;
import org.sosy_lab.java_smt.api.BooleanFormulaManager;
import org.sosy_lab.java_smt.api.SolverContext;
import org.sosy_lab.java_smt.api.SolverException;

import java.util.*;
import java.util.stream.Collectors;

import static com.dat3m.dartagnan.configuration.OptionNames.BASE_METHOD;
import static com.dat3m.dartagnan.solver.caat.CAATSolver.Status.*;
import static com.dat3m.dartagnan.verification.ResultStatus.*;
import static com.dat3m.dartagnan.utils.Utils.toTimeString;
import static java.util.Collections.singleton;
import static java.util.function.Predicate.not;
import static java.util.function.UnaryOperator.identity;
import static java.util.stream.Collectors.toMap;

/*
    Axiom refinement is a custom solving procedure that starts with free memory model axioms and iteratively refines
    their derivations from base relations to perform a verification task.
    It can be understood as a hybrid eager-lazy offline-SMT solver.
    More concretely, it iteratively
        - finds some assertion-violating execution w.r.t. (possibly free) memory model axioms
        - checks the consistency of this execution using a custom theory solver (CAAT-Solver)
        - refines the derivation of axioms from base relations if the found execution was inconsistent, using the
          explanations provided by the theory solver.
 */
@Options
public class AxiomRefinementSolver extends RefinementSolver {

    private static final Logger logger = LoggerFactory.getLogger(AxiomRefinementSolver.class);

    // ================================================================================================================
    // Configuration

    @Option(name=BASE_METHOD,
            description="Used method to solve all non-eazy constraints.",
            secure=true,
            toUppercase=true)
    private Method baseMethod = Method.getDefault();

    // ================================================================================================================
    // Data classes

    private record RefinementIteration(
            SMTStatus smtStatus,
            long nativeSmtTime,
            long caatTime,
            long refineTime,
            // The following are only meaningful if <smtStatus>==SAT
            CAATSolver.Status caatStatus,
            BooleanFormula refinementFormula,
            // The following are only for statistics keeping
            EazyWMMSolver.Statistics caatStats,
            DNF<CoreLiteral> inconsistencyReasons,
            Conjunction<CoreImplication> inconsistencyImplications,
            List<Event> observedEvents
    ) {
        public boolean isInconclusive() { return smtStatus == SMTStatus.SAT && caatStatus == INCONSISTENT; }
        public boolean isConclusive() { return !isInconclusive(); }
    }

    private record RefinementTrace(List<RefinementIteration> iterations) {
        public RefinementIteration getFinalIteration() { return iterations.get(iterations.size() - 1); }

        public SMTStatus getFinalResult() {
            final RefinementIteration finalIteration = getFinalIteration();
            if (finalIteration.smtStatus != SMTStatus.SAT) {
                return finalIteration.smtStatus;
            } else if (finalIteration.caatStatus == CONSISTENT) {
                return SMTStatus.SAT;
            } else {
                return SMTStatus.UNKNOWN;
            }
        }

        public long getNativeSmtTime() { return iterations.stream().mapToLong(RefinementIteration::nativeSmtTime).sum(); }
        public long getCaatTime() { return iterations.stream().mapToLong(RefinementIteration::caatTime).sum(); }
        public long getRefiningTime() { return iterations.stream().mapToLong(RefinementIteration::refineTime).sum(); }

        public Set<Event> getObservedEvents() {
            return iterations.stream().filter(iter -> iter.observedEvents != null)
                    .flatMap(iter -> iter.observedEvents.stream()).collect(Collectors.toSet());
        }

        public List<BooleanFormula> getRefinementFormulas() {
            return iterations.stream().filter(iter -> iter.refinementFormula != null)
                    .map(RefinementIteration::refinementFormula).toList();
        }

        public RefinementTrace concat(RefinementTrace other) {
            return new RefinementTrace(Lists.newArrayList(Iterables.concat(this.iterations, other.iterations)));
        }
    }

    // ================================================================================================================
    // Axiom refinement solver

    private AxiomRefinementSolver(VerificationTask task) throws InvalidConfigurationException {
        super(task);
        task.getConfig().inject(this);
        if (baseMethod == Method.EAZY) {
            throw new UnsupportedOperationException(BASE_METHOD + " \"" + Method.EAZY.name() + "\" is not supported");
        }
    }

    public static AxiomRefinementSolver create(VerificationTask task) throws InvalidConfigurationException  {
        return new AxiomRefinementSolver(task);
    }

    //TODO: We do not yet use Witness information.
    @Override
    protected void runInternal()
            throws InterruptedException, SolverException, InvalidConfigurationException {
        final VerificationTask task = (VerificationTask) this.task;
        final Program program = task.getProgram();
        final Wmm memoryModel = task.getMemoryModel();
        final Configuration config = task.getConfig();

        // ------------------------ Preprocessing / Analysis ------------------------
        final Collection<Constraint> biases = addBiases(memoryModel);
        preprocess(task);

        EmptinessToIrreflexivity.newInstance().run(memoryModel);
        SimplifyIrreflexivities.fromConfig(config).run(memoryModel);
        MergeIrreflexivities.newInstance().run(memoryModel);
        RemoveDeadRelations.newInstance().run(memoryModel);

        final Context analysisContext = Context.create();
        performStaticProgramAnalyses(task, analysisContext, config);
        performStaticWmmAnalyses(task, analysisContext, config);
        performIntervalAnalysis(task, analysisContext, config);

        //  ------- Generate refinement model -------
        final Collection<Constraint> wmmConstraintsToEncode = new LinkedHashSet<>(biases);
        // The cut has to be encoded.
        wmmConstraintsToEncode.addAll(generateCut(memoryModel));
        // We want to encode all acyclicity axioms but without dependencies
        final Map<Constraint, Constraint> constraintsToEazyConstraints = getConstraintsToEazyConstraints(memoryModel.getAxioms(), wmmConstraintsToEncode);
        final Collection<Constraint> eazyConstraints = constraintsToEazyConstraints.values();
        wmmConstraintsToEncode.addAll(eazyConstraints);
        wmmConstraintsToEncode.addAll(getNonStaticBaseConstraints(constraintsToEazyConstraints.keySet(), analysisContext));
        if (baseMethod == Method.EAGER) {
            wmmConstraintsToEncode.addAll(memoryModel.getAxioms().stream()
                    .filter(not(eazyConstraints::contains))
                    .toList());
        }

        // ------------------------ Encoding ------------------------
        initSMTSolver(config);
        final SolverContext ctx = this.solverContext;
        final ProverWithTracker prover = this.prover;

        context = EncodingContext.of(task, analysisContext, ctx.getFormulaManager(), wmmConstraintsToEncode);
        final TrivialImplications trivialImplications = getTrivialImplications(eazyConstraints);
        final ProgramEncoder programEncoder = ProgramEncoder.withContext(context);
        final WmmEncoder baselineEncoder = WmmEncoder.withContext(context);
        final PropertyEncoder propertyEncoder = PropertyEncoder.withContext(context, baselineEncoder);
        final SymmetryEncoder symmetryEncoder = SymmetryEncoder.withContext(context);

        final BooleanFormulaManager bmgr = ctx.getFormulaManager().getBooleanFormulaManager();
        final boolean computeCoreReasons = baseMethod == Method.LAZY;
        final EazyWMMSolver solver = EazyWMMSolver.withContext(context, eazyConstraints, trivialImplications, computeCoreReasons);
        final EazyRefiner refiner = EazyRefiner.newInstance();
        final Property.Type propertyType = Property.getCombinedType(task.getProperties(), task);

        logger.info("Starting encoding using {}", ctx.getVersion());
        prover.writeComment("Program encoding");
        prover.addConstraint(programEncoder.encodeFullProgram());
        prover.writeComment("Memory model (baseline) encoding");
        prover.addConstraint(baselineEncoder.encodeFullMemoryModel());
        prover.writeComment("Trivial implications from base relations to axioms");
        prover.addConstraint(trivialImplications.encode(context));
        prover.writeComment("Symmetry breaking encoding");
        prover.addConstraint(symmetryEncoder.encodeFullSymmetryBreaking());
        // Bounds
        prover.writeComment("Bounds over variables");
        prover.addConstraint(programEncoder.encodeBounds());

        // ------------------------ Solving ------------------------
        logger.info("Axiom refinement procedure started.");

        logger.info("Checking target property.");
        prover.push();
        prover.writeComment("Property encoding");
        prover.addConstraint(propertyEncoder.encodeProperties(task.getProperties()));

        final RefinementTrace propertyTrace = runRefinement(task, prover, solver, refiner);
        SMTStatus smtStatus = propertyTrace.getFinalResult();

        if (smtStatus == SMTStatus.UNKNOWN) {
            // Refinement got no result (should not be able to happen), so we cannot proceed further.
            logger.warn("Axiom refinement procedure was inconclusive. Trying to find reason of inconclusiveness.");
            analyzeInconclusiveness(task, analysisContext, solver.getExecution());
            throw new RuntimeException("Terminated verification due to inconclusiveness (bug?).");
        }

        if (logger.isInfoEnabled()) {
            final String message = switch (smtStatus) {
                case SAT -> propertyType == Property.Type.SAFETY ? "Specification violation found."
                        : "Specification witness found.";
                case UNSAT -> propertyType == Property.Type.SAFETY ? "Bounded specification proven."
                        : "Bounded specification falsified.";
                // Cannot be reached due to the above checks.
                default -> throw new RuntimeException("unreachable");
            };
            logger.info(message);
        }

        RefinementTrace combinedTrace = propertyTrace;

        long boundCheckTime = 0;
        if (smtStatus == SMTStatus.UNSAT) {
            // Do bound check
            logger.info("Checking unrolling bounds.");
            final long lastTime = System.currentTimeMillis();
            prover.pop();
            prover.writeComment("Bound encoding");
            prover.addConstraint(propertyEncoder.encodeBoundEventExec());
            // Add back the refinement clauses we already found, hoping that this improves the performance.
            prover.writeComment("Refinement encoding");
            prover.addConstraint(bmgr.and(propertyTrace.getRefinementFormulas()));
            final RefinementTrace boundTrace = runRefinement(task, prover, solver, refiner);
            boundCheckTime = System.currentTimeMillis() - lastTime;

            smtStatus = boundTrace.getFinalResult();
            combinedTrace = combinedTrace.concat(boundTrace);
            res = smtStatus == SMTStatus.UNSAT ? PASS : UNKNOWN;

            if (logger.isInfoEnabled()) {
                final String message = switch (smtStatus) {
                    case UNKNOWN -> "Bound check was inconclusive (bug?)";
                    case SAT -> "Bounds are reachable: Unbounded specification unknown.";
                    case UNSAT -> "Bounds are unreachable: Unbounded specification proven.";
                };
                logger.info(message);
            }
        } else {
            res = FAIL;
        }

        // -------------------------- Report statistics summary --------------------------

        if (logger.isInfoEnabled()) {
            logger.info(generateSummary(combinedTrace, boundCheckTime, computeCoreReasons));
        }

        if (logger.isDebugEnabled()) {
            logProverStatistics(logger, prover);
        }

        printCovReport(combinedTrace.getObservedEvents(), program, analysisContext);

        // For Safety specs, we have SAT=FAIL, but for reachability specs, we have
        // SAT=PASS
        res = propertyType == Property.Type.SAFETY ? res : res.invert();

        if (hasModel()) {
            validateModel(solver.getExecution());
        }
        logger.info("Verification finished with result {}", res);
    }

    // ================================================================================================================
    // Axiom refinement core algorithm

    // TODO: We could expose the following method(s) to allow for more general application of refinement.
    private RefinementTrace runRefinement(Task task, ProverWithTracker prover, EazyWMMSolver solver, EazyRefiner refiner)
            throws SolverException, InterruptedException {

        final List<RefinementIteration> trace = new ArrayList<>();
        boolean isFinalIteration = false;
        while (!isFinalIteration) {
            checkForInterrupts();
            final RefinementIteration iteration = doRefinementIteration(prover, solver, refiner);
            trace.add(iteration);
            isFinalIteration = !checkProgress(trace) || iteration.isConclusive();

            // ------------------------- Debugging/Logging -------------------------
            if (logger.isDebugEnabled()) {
                // ---- Internal SMT stats after the first iteration ----
                if (trace.size() == 1) {
                    StringBuilder smtStatistics = new StringBuilder(
                            "\n ===== SMT Statistics (after first iteration) ===== \n");
                    for (String key : prover.getStatistics().keySet()) {
                        smtStatistics.append(String.format("\t%s -> %s\n", key, prover.getStatistics().get(key)));
                    }
                    logger.debug(smtStatistics.toString());
                }

                // ---- Debug iteration stats ----
                final StringBuilder debugMessage = new StringBuilder();
                debugMessage.append("\n").append(String.format("""
                        ===== Solver iteration: %d =====
                        Native solving time(ms): %s
                        """, trace.size(), iteration.nativeSmtTime));
                if (!isFinalIteration) {
                    debugMessage.append(iteration.caatStats);
                }
                logger.debug(debugMessage.toString());

                // ---- Trace iteration stats ----
                if (logger.isTraceEnabled() && !isFinalIteration) {
                    final StringBuilder traceMessage = new StringBuilder().append("Found inconsistency reasons:\n");
                    for (Conjunction<CoreLiteral> cube : iteration.inconsistencyReasons.getCubes()) {
                        traceMessage.append(cube).append("\n");
                    }

                    traceMessage.append("Found inconsistency implications:\n");
                    for (CoreImplication implication : iteration.inconsistencyImplications.getLiterals()) {
                        traceMessage.append(implication).append("\n");
                    }
                    logger.trace(traceMessage.toString());
                }
            }
        }

        return new RefinementTrace(trace);
    }

    private boolean checkProgress(List<RefinementIteration> trace) {
        if (trace.size() < 2 || trace.get(trace.size() - 1).isConclusive()) {
            return true;
        }
        final RefinementIteration last = trace.get(trace.size() - 1);
        final RefinementIteration prev = trace.get(trace.size() - 2);
        return !(last.inconsistencyReasons.equals(prev.inconsistencyReasons) && last.inconsistencyImplications.equals(prev.inconsistencyImplications));
    }

    // ================================================================================================================
    // Special memory model processing

    private RefinementIteration doRefinementIteration(ProverWithTracker prover, EazyWMMSolver solver, EazyRefiner refiner)
            throws SolverException, InterruptedException {

        long nativeTime;
        long caatTime = 0;
        long refineTime = 0;
        CAATSolver.Status caatStatus = INCONCLUSIVE;
        BooleanFormula refinementFormula = null;
        EazyWMMSolver.Statistics caatStats = null;
        DNF<CoreLiteral> inconsistencyReasons = null;
        Conjunction<CoreImplication> inconsistencyImplications = null;
        List<Event> observedEvents = null;

        // ------------ Native SMT solving ------------
        long lastTime = System.currentTimeMillis();
        final SMTStatus smtStatus = prover.isUnsat() ? SMTStatus.UNSAT : SMTStatus.SAT;
        nativeTime = (System.currentTimeMillis() - lastTime);

        if (smtStatus == SMTStatus.SAT) {
            // ------------ CAAT solving ------------
            lastTime = System.currentTimeMillis();
            final EazyWMMSolver.Result solverResult;
            try (IREvaluator model = context.newEvaluator(prover)) {
                solverResult = solver.check(model);
            } catch (SolverException e) {
                logger.error(e.getMessage());
                throw e;
            }
            caatTime = (System.currentTimeMillis() - lastTime);

            observedEvents = new ArrayList<>(Lists.transform(solver.getExecution().getEventList(), EventData::getEvent));
            caatStatus = solverResult.getStatus();
            caatStats = solverResult.getStatistics();
            if (caatStatus == INCONSISTENT) {
                // ------------ Refining ------------
                inconsistencyReasons = solverResult.getCoreReasons();
                inconsistencyImplications = solverResult.getCoreImplications();
                lastTime = System.currentTimeMillis();
                refinementFormula = refiner.refine(inconsistencyReasons, inconsistencyImplications, context);
                prover.writeComment("Refinement encoding");
                prover.addConstraint(refinementFormula);
                refineTime = (System.currentTimeMillis() - lastTime);
            }
        }

        return new RefinementIteration(
                smtStatus, nativeTime, caatTime, refineTime, caatStatus,
                refinementFormula, caatStats, inconsistencyReasons, inconsistencyImplications, observedEvents
        );
    }

    private Map<Constraint, Constraint> getConstraintsToEazyConstraints(Collection<Axiom> axioms, Collection<Constraint> wmmConstraintsToEncode) {
        final Map<Constraint, Constraint> acyclicity = axioms.stream()
                .filter(Acyclicity.class::isInstance)
                .filter(not(wmmConstraintsToEncode::contains))
                .collect(toMap(identity(), a -> new Acyclicity(a.getRelation(), a.isNegated(), a.isFlagged()) {
                    @Override
                    public List<? extends Relation> getConstrainedRelations() {
                        return Collections.emptyList();
                    }
                }));

        final Map<Constraint, Constraint> irreflexivity = axioms.stream()
                .filter(Irreflexivity.class::isInstance)
                .map(Irreflexivity.class::cast)
                .filter(not(wmmConstraintsToEncode::contains))
                .filter(a -> a.getComponents().size() > 1)
                .collect(toMap(identity(), EazyIrreflexivity::new));

        final Map<Constraint, Constraint> constraintsToEazyConstraints = new HashMap<>(acyclicity);
        constraintsToEazyConstraints.putAll(irreflexivity);
        return constraintsToEazyConstraints;
    }

    private Collection<Constraint> getNonStaticBaseConstraints(Collection<Constraint> origEazyConstraints, Context analysisContext) {
        final RelationAnalysis ra = analysisContext.requires(RelationAnalysis.class);
        final Collection<Constraint> nonStaticBaseConstraints = new ArrayList<>();
        final Set<Constraint> visited = new HashSet<>();
        for (Constraint eazyConstraint : origEazyConstraints) {
            final Deque<Constraint> visitingStack = new ArrayDeque<>();
            visitingStack.push(eazyConstraint);
            while (!visitingStack.isEmpty()) {
                final Constraint constraint = visitingStack.pop();
                if (visited.add(constraint)) {
                    final Collection<? extends Constraint> deps = Wmm.computeConstraintDependencies(constraint);
                    if (deps.isEmpty()) {
                        if (!nonStaticBaseConstraints.contains(constraint)) {
                            final Relation baseRel = constraint.getConstrainedRelations().get(0);
                            final RelationAnalysis.Knowledge k = ra.getKnowledge(baseRel);
                            if (k.getMaySet().size() != k.getMustSet().size()) {
                                nonStaticBaseConstraints.add(constraint);
                            }
                        }
                    } else {
                        for (Constraint dep : deps) {
                            visitingStack.push(dep);
                        }
                    }
                }
            }
        }
        return nonStaticBaseConstraints;
    }

    private record DefinitionWithConditions(Definition definition, List<EventGraph> sideConditions) {
    }

    private TrivialImplications getTrivialImplications(Collection<? extends Constraint> eazyConstraints) {
        final RelationAnalysis ra = context.getAnalysisContext().requires(RelationAnalysis.class);
        final Map<Relation, Map<Relation, Map<Event, List<Event>>>> result = new HashMap<>();
        for (Constraint eazyConstraint : eazyConstraints) {
            for (final Definition eazyDef : getEazyDefinitions(eazyConstraint)) {
                final Relation eazyRel = eazyDef.getDefinedRelation();
                if (!result.containsKey(eazyRel)) {
                    result.put(eazyRel, getTrivialImplications(eazyRel, ra));
                }
            }
        }
        return new TrivialImplications(result);
    }

    private static Collection<Definition> getEazyDefinitions(final Constraint eazyConstraint) {
        if (eazyConstraint instanceof Definition) {
            return singleton((Definition) eazyConstraint);
        } else if (eazyConstraint instanceof final Axiom eazyAxiom) {
            if (eazyAxiom instanceof final EazyIrreflexivity irreflexivity) {
                return irreflexivity.getComponents();
            } else {
                return singleton(eazyAxiom.getRelation().getDefinition());
            }
        } else {
            throw new IllegalStateException("Unsupported eazy constraint type: " + eazyConstraint.getClass().getSimpleName());
        }
    }

    private Map<Relation, Map<Event, List<Event>>> getTrivialImplications(final Relation eazyRel, final RelationAnalysis ra) {
        final Set<Definition> visited = new HashSet<>();
        final Map<Relation, Map<Event, List<Event>>> trivialImplications = new HashMap<>();
        final EventGraph must = ra.getKnowledge(eazyRel).getMustSet();

        final List<Definition> foundDefs = new ArrayList<>();
        final Deque<DefinitionWithConditions> visitingStack = new ArrayDeque<>();
        visitingStack.add(new DefinitionWithConditions(eazyRel.getDefinition(), new ArrayList<>()));
        while (!visitingStack.isEmpty()) {
            final DefinitionWithConditions constraintWithConditions = visitingStack.pop();
            final Definition definition = constraintWithConditions.definition;
            if (visited.add(definition)) {
                if (context.isEncoded(definition)) {
                    if (!foundDefs.contains(definition)) {
                        final Relation rel = definition.getDefinedRelation();
                        final Map<Event, List<Event>> events = new HashMap<>();
                        ra.getKnowledge(rel).getMaySet().apply((e1, e2) -> {
                            if (!must.contains(e1, e2)) {
                                for (EventGraph sideCondition : constraintWithConditions.sideConditions) {
                                    if (!sideCondition.contains(e1, e2)) {
                                        return;
                                    }
                                }
                                events.computeIfAbsent(e1, k -> new ArrayList<>()).add(e2);
                            }
                        });
                        trivialImplications.put(rel, events);
                        foundDefs.add(definition);
                    }
                } else if (definition instanceof Union || definition instanceof SetIdentity
                        || definition instanceof TransitiveClosure) {
                    for (Constraint dep : Wmm.computeConstraintDependencies(definition)) {
                        visitingStack.push(new DefinitionWithConditions((Definition) dep, constraintWithConditions.sideConditions));
                    }
                } else if (definition instanceof Intersection intersection) {
                    final List<Relation> operands = intersection.getOperands();
                    for (Relation operand : operands) {
                        final RelationAnalysis.Knowledge k = ra.getKnowledge(operand);
                        final EventGraph mayOperands = k.getMaySet();
                        final EventGraph mustOperands = k.getMustSet();
                        final int unknownSize = mayOperands.size() - mustOperands.size();
                        if (unknownSize != 0) {
                            final List<EventGraph> sideConditions = new ArrayList<>(constraintWithConditions.sideConditions);
                            boolean hasEmptySideCondition = false;
                            for (Relation otherOperand : operands) {
                                if (operand != otherOperand) {
                                    final EventGraph otherMustOperands = ra.getKnowledge(otherOperand).getMustSet();
                                    if (!otherMustOperands.isEmpty()) {
                                        sideConditions.add(otherMustOperands);
                                    } else {
                                        hasEmptySideCondition = true;
                                        break;
                                    }
                                }
                            }
                            if (!hasEmptySideCondition) {
                                visitingStack.push(new DefinitionWithConditions(operand.getDefinition(), sideConditions));
                            }
                        }
                    }
                } else if (definition instanceof Composition composition) {
                    final Relation left = composition.getLeftOperand();
                    final Relation right = composition.getRightOperand();
                    for (Relation operand : new Relation[] {left, right}) {
                        final RelationAnalysis.Knowledge k = ra.getKnowledge(operand);
                        final EventGraph mayOperands = k.getMaySet();
                        final EventGraph mustOperands = k.getMustSet();
                        final int unknownSize = mayOperands.size() - mustOperands.size();
                        if (unknownSize != 0) {
                            final boolean isLeft = operand == left;
                            final EventGraph otherMustOperands = ra.getKnowledge(isLeft ? right : left).getMustSet();
                            final Map<Event, Set<Event>> mayMap = isLeft ? mayOperands.getInMap() : mayOperands.getOutMap();
                            final Map<Event, Set<Event>> localSideConditions = new HashMap<>();
                            for (Event otherMustOperand : otherMustOperands.getDomain()) {
                                if (otherMustOperands.contains(otherMustOperand, otherMustOperand)) {
                                    final Set<Event> maySet = mayMap.get(otherMustOperand);
                                    if (maySet != null) {
                                        for (Event mayOperand : maySet) {
                                            if (isLeft) {
                                                localSideConditions.computeIfAbsent(mayOperand, key -> new HashSet<>())
                                                        .add(otherMustOperand);
                                            } else {
                                                localSideConditions.computeIfAbsent(otherMustOperand, key -> new HashSet<>())
                                                        .add(mayOperand);
                                            }
                                        }
                                    }
                                }
                            }
                            if (!localSideConditions.isEmpty()) {
                                final List<EventGraph> sideConditions = new ArrayList<>(constraintWithConditions.sideConditions);
                                sideConditions.add(new MapEventGraph(localSideConditions));
                                visitingStack.push(new DefinitionWithConditions(operand.getDefinition(), sideConditions));
                            }
                        }
                    }
                }
            }
        }
        return trivialImplications;
    }

    // ================================================================================================================
    // Statistics & Debugging

    private static String generateSummary(RefinementTrace trace, long boundCheckTime, boolean hasReasons) {
        final List<EazyWMMSolver.Statistics> statList = trace.iterations.stream()
                .filter(iter -> iter.caatStats != null).map(RefinementIteration::caatStats).toList();
        final long totalNativeSolvingTime = trace.getNativeSmtTime();
        final long totalCaatTime = trace.getCaatTime();
        final long totalRefiningTime = trace.getRefiningTime();

        long totalModelExtractTime = 0;
        long totalPopulationTime = 0;
        long totalConsistencyCheckTime = 0;
        long totalReasonComputationTime = 0;
        long totalImplicationComputationTime = 0;
        long totalNumReasons = 0;
        long totalNumReducedReasons = 0;
        long totalNumImplications = 0;
        long totalModelSize = 0;
        long minModelSize = Long.MAX_VALUE;
        long maxModelSize = Long.MIN_VALUE;

        for (EazyWMMSolver.Statistics stats : statList) {
            totalModelExtractTime += stats.getModelExtractionTime();
            totalPopulationTime += stats.getPopulationTime();
            totalConsistencyCheckTime += stats.getConsistencyCheckTime();
            if (hasReasons) {
                totalReasonComputationTime += stats.getBaseReasonComputationTime() + stats.getCoreReasonComputationTime();
                totalNumReasons += stats.getNumComputedCoreReasons();
                totalNumReducedReasons += stats.getNumComputedReducedCoreReasons();
            }
            totalImplicationComputationTime +=
                    stats.getBaseImplicationComputationTime() + stats.getCoreImplicationComputationTime();
            totalNumImplications += stats.getNumComputedCoreImplications();

            totalModelSize += stats.getModelSize();
            minModelSize = Math.min(stats.getModelSize(), minModelSize);
            maxModelSize = Math.max(stats.getModelSize(), maxModelSize);
        }

        StringBuilder message = new StringBuilder().append("Summary").append("\n")
                .append(" ======== Summary ========").append("\n")
                .append("Number of iterations: ").append(trace.iterations.size()).append("\n")
                .append("Total native solving time: ").append(toTimeString(totalNativeSolvingTime)).append("\n")
                .append("   -- Bound check time: ").append(toTimeString(boundCheckTime)).append("\n")
                .append("Total CAAT solving time: ").append(toTimeString(totalCaatTime)).append("\n")
                .append("   -- Model extraction time: ").append(toTimeString(totalModelExtractTime)).append("\n")
                .append("   -- Population time: ").append(toTimeString(totalPopulationTime)).append("\n");
        if (hasReasons) {
            message.append("   -- Consistency check time: ").append(toTimeString(totalConsistencyCheckTime)).append("\n")
                    .append("   -- Reason computation time: ").append(toTimeString(totalReasonComputationTime)).append("\n");
        }
        message.append("   -- Implication computation time: ")
                .append(toTimeString(totalImplicationComputationTime)).append("\n")
                .append("   -- Refining time: ").append(toTimeString(totalRefiningTime)).append("\n");
        if (hasReasons) {
            message.append("   -- #Computed core reasons: ").append(totalNumReasons).append("\n")
                    .append("   -- #Computed core reduced reasons: ").append(totalNumReducedReasons).append("\n");
        }
        message.append("   -- #Computed core implications: ").append(totalNumImplications).append("\n");
        if (!statList.isEmpty()) {
            message.append("   -- Min model size (#events): ").append(minModelSize).append("\n")
                    .append("   -- Average model size (#events): ").append(totalModelSize / statList.size())
                    .append("\n")
                    .append("   -- Max model size (#events): ").append(maxModelSize).append("\n");
        }

        return message.toString();
    }

    public static class EazyIrreflexivity extends Irreflexivity {
        public EazyIrreflexivity(final Irreflexivity irreflexivity) {
            super(irreflexivity.getRelation(), irreflexivity.isNegated(), irreflexivity.isFlagged());
        }

        @Override
        public List<? extends Relation> getConstrainedRelations() {
            return Collections.emptyList();
        }

        @Override
        public <T> T accept(Visitor<? extends T> visitor) {
            return visitor.visitEazyIrreflexivity(this);
        }
    }
}