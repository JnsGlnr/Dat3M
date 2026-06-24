package com.dat3m.dartagnan.solver.caat4wmm;


import com.dat3m.dartagnan.encoding.EncodingContext;
import com.dat3m.dartagnan.encoding.IREvaluator;
import com.dat3m.dartagnan.solver.caat.CAATSolver;
import com.dat3m.dartagnan.solver.caat.constraints.AcyclicityConstraint;
import com.dat3m.dartagnan.solver.caat.constraints.Constraint;
import com.dat3m.dartagnan.solver.caat4wmm.coreReasoning.CoreImplication;
import com.dat3m.dartagnan.solver.caat4wmm.coreReasoning.CoreLiteral;
import com.dat3m.dartagnan.solver.caat4wmm.coreReasoning.TrivialImplications;
import com.dat3m.dartagnan.utils.logic.Conjunction;
import com.dat3m.dartagnan.utils.logic.DNF;
import org.sosy_lab.common.configuration.InvalidConfigurationException;

import java.util.*;

/*
    This is our domain-specific bridging component that specializes the CAATSolver to the WMM setting.
*/
public class EazyWMMSolver extends WMMSolver {

    private final TrivialImplications trivialImplications;
    private final boolean computeCoreReasons;

    private EazyWMMSolver(EncodingContext c, TrivialImplications trivialImplications, boolean computeCoreReasons)
            throws InvalidConfigurationException {
        super(c, new ExecutionGraph(c.getTask().getMemoryModel(), constraint -> c.isEncoded(constraint) && !trivialImplications.isEazy(constraint)));
        this.trivialImplications = trivialImplications;
        this.computeCoreReasons = computeCoreReasons;
    }

    public static EazyWMMSolver withContext(EncodingContext context, TrivialImplications trivialImplications, boolean computeCoreReasons)
            throws InvalidConfigurationException {
        return new EazyWMMSolver(context, trivialImplications, computeCoreReasons);
    }

    public Result check(IREvaluator model) {
        // ============ Extract ExecutionModel ==============
        long curTime = System.currentTimeMillis();
        executionModel.initialize(model);
        executionGraph.initializeFromModel(executionModel);
        long extractTime = System.currentTimeMillis() - curTime;

        // ============== Run the CAATSolver ==============
        CAATSolver.Result caatResult = solver.simpleCheck(executionGraph.getCAATModel());
        Result result = Result.fromCAATResult(this, caatResult);
        Statistics stats = result.stats;
        stats.modelExtractionTime = extractTime;
        stats.modelSize = executionGraph.getDomain().size();

        if (result.getStatus() == CAATSolver.Status.INCONSISTENT) {
            final List<Constraint> eazyConstraints = new ArrayList<>();
            final List<Constraint> lazyConstraints = new ArrayList<>();
            for (Constraint violatedConstraint : caatResult.getViolatedConstraints()) {
                if (violatedConstraint instanceof AcyclicityConstraint) {
                    eazyConstraints.add(violatedConstraint);
                } else if (computeCoreReasons) {
                    lazyConstraints.add(violatedConstraint);
                }
            }

            // ============== Compute Core implications ==============
            curTime = System.currentTimeMillis();
            result.coreImplications = reasoner.toCoreImplications(solver.computeInconsistencyImplications(eazyConstraints), trivialImplications);
            stats.numComputedCoreImplications = result.coreImplications.getSize();
            stats.coreImplicationComputationTime = System.currentTimeMillis() - curTime;

            if (computeCoreReasons) {
                // ============== Compute Core reasons ==============
                curTime = System.currentTimeMillis();
                Set<Conjunction<CoreLiteral>> coreReasons = reasoner.toCoreReasons(solver.computeInconsistencyReasons(lazyConstraints));
                stats.numComputedCoreReasons = coreReasons.size();
                result.coreReasons = new DNF<>(coreReasons);
                stats.numComputedReducedCoreReasons = result.coreReasons.getNumberOfCubes();
                stats.coreReasonComputationTime = System.currentTimeMillis() - curTime;
            } else {
                result.coreReasons = DNF.FALSE();
            }
        }

        return result;
    }


    // ===================== Classes ======================

    public class Result extends WMMSolver.Result {
        private Conjunction<CoreImplication> coreImplications;
        private Statistics stats;

        public Conjunction<CoreImplication> getCoreImplications() { return coreImplications; }
        public Statistics getStatistics() { return stats; }

        static Result fromCAATResult(EazyWMMSolver wmmSolver, CAATSolver.Result caatResult) {
            Result result = wmmSolver.new Result();
            result.status = caatResult.getStatus();
            result.stats = wmmSolver.new Statistics();
            result.stats.caatStats = caatResult.getStatistics();

            return result;
        }

        @Override
        public String toString() {
            return status + "\n" +
                    (computeCoreReasons ? coreReasons + "\n" : "") +
                    coreImplications + "\n" +
                    stats;
        }
    }

    public class Statistics extends WMMSolver.Statistics {
        CAATSolver.Statistics caatStats;
        long modelExtractionTime;
        long coreReasonComputationTime;
        long coreImplicationComputationTime;
        int modelSize;
        int numComputedCoreReasons;
        int numComputedReducedCoreReasons;
        int numComputedCoreImplications;

        public long getModelExtractionTime() { return modelExtractionTime; }
        public long getPopulationTime() { return caatStats.getPopulationTime(); }
        public long getBaseReasonComputationTime() { return caatStats.getReasonComputationTime(); }
        public long getCoreReasonComputationTime() { return coreReasonComputationTime; }
        public long getBaseImplicationComputationTime() { return caatStats.getImplicationComputationTime(); }
        public long getCoreImplicationComputationTime() { return coreImplicationComputationTime; }
        public long getConsistencyCheckTime() { return caatStats.getConsistencyCheckTime(); }
        public int getModelSize() { return modelSize; }
        public int getNumComputedBaseReasons() { return caatStats.getNumComputedReasons(); }
        public int getNumComputedReducedBaseReasons() { return caatStats.getNumComputedReducedReasons(); }
        public int getNumComputedCoreReasons() { return numComputedCoreReasons; }
        public int getNumComputedReducedCoreReasons() { return numComputedReducedCoreReasons; }
        public int getNumComputedBaseImplications() { return caatStats.getNumComputedImplications(); }
        public int getNumComputedCoreImplications() { return numComputedCoreImplications; }

        public String toString() {
            StringBuilder str = new StringBuilder();
            str.append("Model extraction time(ms): ").append(getModelExtractionTime()).append("\n");
            str.append("Population time(ms): ").append(getPopulationTime()).append("\n");
            str.append("Consistency check time(ms): ").append(getConsistencyCheckTime()).append("\n");
            if (computeCoreReasons) {
                str.append("Base Reason computation time(ms): ").append(getBaseReasonComputationTime()).append("\n");
                str.append("Core Reason computation time(ms): ").append(getCoreReasonComputationTime()).append("\n");
            }
            str.append("Core Implication computation time(ms): ").append(getBaseImplicationComputationTime())
                    .append("\n");
            str.append("Core Implication computation time(ms): ").append(getCoreImplicationComputationTime())
                    .append("\n");
            str.append("Model size (#events): ").append(getModelSize()).append("\n");
            if (computeCoreReasons) {
                str.append("#Computed reasons (base/core): ").append(getNumComputedBaseReasons())
                        .append("/").append(getNumComputedCoreReasons()).append("\n");
                str.append("#Computed reduced reasons (base/core): ").append(getNumComputedReducedBaseReasons())
                        .append("/").append(getNumComputedReducedCoreReasons()).append("\n");
            }
            str.append("#Computed implications (base/core): ").append(getNumComputedBaseImplications())
                    .append("/").append(getNumComputedCoreImplications()).append("\n");
            return str.toString();
        }
    }

}
