package com.dat3m.dartagnan.verification.solving;

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
import com.dat3m.dartagnan.verification.VerificationTask;
import com.dat3m.dartagnan.verification.model.EventData;
import com.dat3m.dartagnan.wmm.Constraint;
import com.dat3m.dartagnan.wmm.Definition;
import com.dat3m.dartagnan.wmm.Relation;
import com.dat3m.dartagnan.wmm.Wmm;
import com.dat3m.dartagnan.wmm.analysis.RelationAnalysis;
import com.dat3m.dartagnan.wmm.axiom.Acyclicity;
import com.dat3m.dartagnan.wmm.axiom.Axiom;
import com.dat3m.dartagnan.wmm.definition.*;
import com.dat3m.dartagnan.wmm.utils.graph.EventGraph;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.java_smt.api.BooleanFormula;
import org.sosy_lab.java_smt.api.BooleanFormulaManager;
import org.sosy_lab.java_smt.api.SolverContext;
import org.sosy_lab.java_smt.api.SolverException;

import java.util.*;
import java.util.stream.Collectors;

import static com.dat3m.dartagnan.solver.caat.CAATSolver.Status.*;
import static com.dat3m.dartagnan.utils.Result.*;
import static com.dat3m.dartagnan.utils.Utils.toTimeString;
import static java.util.function.Predicate.not;

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
public class AxiomRefinementSolver extends RefinementSolver {

    private static final Logger logger = LoggerFactory.getLogger(AxiomRefinementSolver.class);

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
    }

    public static AxiomRefinementSolver create(VerificationTask task) throws InvalidConfigurationException  {
        return new AxiomRefinementSolver(task);
    }

    //TODO: We do not yet use Witness information.
    @Override
    protected void runInternal()
            throws InterruptedException, SolverException, InvalidConfigurationException {
        final VerificationTask task = this.task;
        final Program program = task.getProgram();
        final Wmm memoryModel = task.getMemoryModel();
        final Configuration config = task.getConfig();

        // ------------------------ Preprocessing / Analysis ------------------------
        final Collection<Constraint> biases = addBiases(memoryModel);
        preprocess(task);

        final Context analysisContext = Context.create();
        performStaticProgramAnalyses(task, analysisContext, config);
        performStaticWmmAnalyses(task, analysisContext, config);
        performIntervalAnalysis(task, analysisContext, config);

        //  ------- Generate refinement model -------
        final Collection<Constraint> wmmConstraintsToEncode = new HashSet<>(biases);
        // The cut has to be encoded.
        wmmConstraintsToEncode.addAll(generateCut(memoryModel));
        // We want to encode all acyclicity axioms but without dependencies
        final Collection<? extends Constraint> eazyConstraints = memoryModel.getAxioms().stream()
                .filter(Acyclicity.class::isInstance)
                .filter(not(wmmConstraintsToEncode::contains))
                .map(a -> new Acyclicity(a.getRelation(), a.isNegated(), a.isFlagged()) {
                    @Override
                    public List<? extends Relation> getConstrainedRelations() {
                        return Collections.emptyList();
                    }
                })
                .toList();
        wmmConstraintsToEncode.addAll(eazyConstraints);

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
        final EazyWMMSolver solver = EazyWMMSolver.withContext(context, trivialImplications);
        final EazyRefiner refiner = EazyRefiner.newInstance();
        final Property.Type propertyType = Property.getCombinedType(task.getProperty(), task);

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
        prover.addConstraint(propertyEncoder.encodeProperties(task.getProperty()));

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
            logger.info(generateSummary(combinedTrace, boundCheckTime));
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

    private RefinementTrace runRefinement(VerificationTask task, ProverWithTracker prover, EazyWMMSolver solver, EazyRefiner refiner)
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

    private record ConstraintWithConditions(Constraint constraint, List<EventGraph> sideConditions) {
    }

    private TrivialImplications getTrivialImplications(Collection<? extends Constraint> eazyConstraints) {
        final RelationAnalysis ra = context.getAnalysisContext().requires(RelationAnalysis.class);
        final Map<Relation, Map<Relation, Map<Event, List<Event>>>> result = new HashMap<>();
        for (Constraint eazyConstraint : eazyConstraints) {
            final Map<Relation, Map<Event, List<Event>>> trivialImplications = new HashMap<>();
            if (eazyConstraint instanceof Axiom eazyAxiom) {
                eazyConstraint = eazyAxiom.getRelation().getDefinition();
            }
            final Definition eazyDef = (Definition) eazyConstraint;
            final Relation eazyRel = eazyDef.getDefinedRelation();
            final EventGraph must = ra.getKnowledge(eazyRel).getMustSet();

            final List<Constraint> foundConstraints = new ArrayList<>();
            final Deque<ConstraintWithConditions> visited = new ArrayDeque<>();
            visited.add(new ConstraintWithConditions(eazyConstraint, new ArrayList<>()));
            while (!visited.isEmpty()) {
                final ConstraintWithConditions constraintWithConditions = visited.pop();
                final Constraint constraint = constraintWithConditions.constraint;
                if (context.isEncoded(constraint)) {
                    if (!foundConstraints.contains(constraint)) {
                        final Definition def = (Definition) constraint;
                        final Relation rel = def.getDefinedRelation();
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
                        foundConstraints.add(constraint);
                    }
                } else if (constraint instanceof Union || constraint instanceof SetIdentity
                        || constraint instanceof TransitiveClosure) {
                    for (Constraint dep : Wmm.computeConstraintDependencies(constraint)) {
                        visited.push(new ConstraintWithConditions(dep, constraintWithConditions.sideConditions));
                    }
                } else if (constraint instanceof Intersection intersection) {
                    final List<Relation> operands = intersection.getOperands();
                    final List<EventGraph> localSideConditions = new ArrayList<>(operands.size() - 1);
                    Relation mostUnknownRelation = null;
                    EventGraph mostUnknownKnownElements = null;
                    int mostUnknownSize = 0;
                    for (Relation operand : operands) {
                        final RelationAnalysis.Knowledge k = ra.getKnowledge(operand);
                        final EventGraph mayOperands = k.getMaySet();
                        final EventGraph mustOperands = k.getMustSet();
                        final int unknownSize = mayOperands.size() - mustOperands.size();
                        if (unknownSize > mostUnknownSize) {
                            if (mostUnknownRelation != null) {
                                localSideConditions.add(mostUnknownKnownElements);
                            }
                            mostUnknownRelation = operand;
                            mostUnknownKnownElements = mustOperands;
                            mostUnknownSize = unknownSize;
                        } else {
                            localSideConditions.add(mustOperands);
                        }
                    }
                    if (mostUnknownRelation != null) {
                        final List<EventGraph> sideConditions = new ArrayList<>(constraintWithConditions.sideConditions);
                        sideConditions.addAll(localSideConditions);
                        visited.push(new ConstraintWithConditions(mostUnknownRelation.getDefinition(), sideConditions));
                    }
                }
            }
            result.put(eazyRel, trivialImplications);
        }
        return new TrivialImplications(result);
    }

    // ================================================================================================================
    // Statistics & Debugging

    private static String generateSummary(RefinementTrace trace, long boundCheckTime) {
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
            totalReasonComputationTime += stats.getBaseReasonComputationTime() + stats.getCoreReasonComputationTime();
            totalImplicationComputationTime +=
                    stats.getBaseImplicationComputationTime() + stats.getCoreImplicationComputationTime();
            totalNumReasons += stats.getNumComputedCoreReasons();
            totalNumReducedReasons += stats.getNumComputedReducedCoreReasons();
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
                .append("   -- Population time: ").append(toTimeString(totalPopulationTime)).append("\n")
                .append("   -- Consistency check time: ").append(toTimeString(totalConsistencyCheckTime)).append("\n")
                .append("   -- Reason computation time: ").append(toTimeString(totalReasonComputationTime)).append("\n")
                .append("   -- Implication computation time: ")
                .append(toTimeString(totalImplicationComputationTime)).append("\n")
                .append("   -- Refining time: ").append(toTimeString(totalRefiningTime)).append("\n")
                .append("   -- #Computed core reasons: ").append(totalNumReasons).append("\n")
                .append("   -- #Computed core reduced reasons: ").append(totalNumReducedReasons).append("\n")
                .append("   -- #Computed core implications: ").append(totalNumImplications).append("\n");
        if (!statList.isEmpty()) {
            message.append("   -- Min model size (#events): ").append(minModelSize).append("\n")
                    .append("   -- Average model size (#events): ").append(totalModelSize / statList.size())
                    .append("\n")
                    .append("   -- Max model size (#events): ").append(maxModelSize).append("\n");
        }

        return message.toString();
    }
}