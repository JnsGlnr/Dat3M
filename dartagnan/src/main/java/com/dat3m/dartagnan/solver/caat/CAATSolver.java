package com.dat3m.dartagnan.solver.caat;


import com.dat3m.dartagnan.solver.caat.constraints.Constraint;
import com.dat3m.dartagnan.solver.caat.misc.PathAlgorithm;
import com.dat3m.dartagnan.solver.caat.reasoning.CAATImplication;
import com.dat3m.dartagnan.solver.caat.reasoning.CAATLiteral;
import com.dat3m.dartagnan.solver.caat.reasoning.Reasoner;
import com.dat3m.dartagnan.utils.logic.Conjunction;
import com.dat3m.dartagnan.utils.logic.DNF;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.dat3m.dartagnan.solver.caat.CAATSolver.Status.CONSISTENT;
import static com.dat3m.dartagnan.solver.caat.CAATSolver.Status.INCONSISTENT;


public class CAATSolver {

    // ======================================== Fields  ==============================================

    private final Reasoner reasoner;

    // The statistics of the last call
    private Statistics stats;

    // ======================================== Construction ==============================================

    private CAATSolver() {
        this.reasoner = new Reasoner();
    }

    public static CAATSolver create() {
        return new CAATSolver();
    }

    // ======================================== Accessors ==============================================

    public Reasoner getReasoner() { return reasoner; }

    public Statistics getStatistics() { return stats; }

    // ======================================== Solving ==============================================

    /*
        <check> assumes the following:
            - The CAATModel <model> has been initialized to some domain (<model.initializeToDomain>)
            - All base predicates are populated or will populate themselves.

        <check> will:
            - Populate the derived predicates in <model>
            - Check consistency of <model>
            - Return results about the computation
     */
    public Result simpleCheck(CAATModel model) {
        Result result = new Result();
        stats = result.getStatistics();

        PathAlgorithm.ensureCapacity(model.getDomain().size());
        // ============== Populate derived predicates ===============
        long curTime = System.currentTimeMillis();
        model.populate();
        stats.populationTime = System.currentTimeMillis() - curTime;

        // ============== Check for inconsistencies ===============
        curTime = System.currentTimeMillis();
        List<Constraint> violatedConstraints = model.getViolatedConstraints();
        result.setViolatedConstraints(violatedConstraints);
        stats.consistencyCheckTime = System.currentTimeMillis() - curTime;

        return result;
    }

    /*
        <check> assumes the following:
            - The CAATModel <model> has been initialized to some domain (<model.initializeToDomain>)
            - All base predicates are populated or will populate themselves.

        <check> will:
            - Populate the derived predicates in <model>
            - Check consistency of <model>
            - If applicable, compute base reasons of consistency violations
            - Return results about the computation
     */
    public Result check(CAATModel model) {
        Result result = simpleCheck(model);

        if (result.getStatus() == INCONSISTENT) {
            // ============== Compute reasons ===============
            result.setBaseReasons(computeInconsistencyReasons(result.getViolatedConstraints()));
        }

        return result;
    }

    // ======================================== Reason computation ==============================================

    public DNF<CAATLiteral> computeInconsistencyReasons(List<Constraint> violatedConstraints) {
        long curTime = System.currentTimeMillis();
        List<Conjunction<CAATLiteral>> reasons = new ArrayList<>();
        for (Constraint constraint : violatedConstraints) {
            reasons.addAll(reasoner.computeViolationReasons(constraint).getCubes());
        }
        stats.numComputedReasons += reasons.size();
        DNF<CAATLiteral> result = new DNF<>(reasons); // The conversion to DNF removes duplicates and dominated clauses
        stats.numComputedReducedReasons += result.getNumberOfCubes();
        stats.reasonComputationTime += (System.currentTimeMillis() - curTime);

        return result;
    }

    public Conjunction<CAATImplication> computeInconsistencyImplications(List<Constraint> violatedConstraints) {
        long curTime = System.currentTimeMillis();
        List<CAATImplication> implications = new ArrayList<>();
        for (Constraint constraint : violatedConstraints) {
            implications.addAll(reasoner.computeViolationImplications(constraint).getLiterals());
        }
        stats.numComputedImplications += implications.size();
        Conjunction<CAATImplication> result = new Conjunction<>(implications);
        stats.implicationComputationTime += (System.currentTimeMillis() - curTime);
        return result;
    }

    // ======================================== Inner Classes ==============================================

    public static class Result {
        private List<Constraint> violatedConstraints;
        private DNF<CAATLiteral> baseReasons;
        private final Statistics stats;

        public Status getStatus() { return violatedConstraints.isEmpty() ? CONSISTENT : INCONSISTENT; }
        public List<Constraint> getViolatedConstraints() { return violatedConstraints; }
        public DNF<CAATLiteral> getBaseReasons() { return baseReasons; }
        public Statistics getStatistics() { return stats; }

        void setViolatedConstraints(List<Constraint> violatedConstraints) { this.violatedConstraints = violatedConstraints; }
        void setBaseReasons(DNF<CAATLiteral> reasons) {
            this.baseReasons = reasons;
        }

        public Result() {
            stats = new Statistics();
            violatedConstraints = Collections.emptyList();
            baseReasons = DNF.FALSE();
        }

        @Override
        public String toString() {
            return getStatus() + "\n" +
                    baseReasons + "\n" +
                    stats;
        }
    }

    public static class Statistics {
        long populationTime;
        long consistencyCheckTime;
        long reasonComputationTime;
        long implicationComputationTime;
        int numComputedReasons;
        int numComputedReducedReasons;
        int numComputedImplications;

        public long getPopulationTime() { return populationTime; }
        public long getReasonComputationTime() { return reasonComputationTime; }
        public long getImplicationComputationTime() { return implicationComputationTime; }
        public long getConsistencyCheckTime() { return consistencyCheckTime; }
        public int getNumComputedReasons() { return numComputedReasons; }
        public int getNumComputedReducedReasons() { return numComputedReducedReasons; }
        public int getNumComputedImplications() { return numComputedImplications; }

        public String toString() {
            StringBuilder str = new StringBuilder();
            str.append("Model construction time(ms): ").append(populationTime).append("\n");
            str.append("Consistency check time(ms): ").append(consistencyCheckTime).append("\n");
            str.append("Reason computation time(ms): ").append(reasonComputationTime).append("\n");
            str.append("Implication computation time(ms): ").append(implicationComputationTime).append("\n");
            str.append("#Computed reasons: ").append(numComputedReasons).append("\n");
            str.append("#Computed reduced reasons: ").append(numComputedReducedReasons).append("\n");
            str.append("#Computed implications: ").append(numComputedImplications).append("\n");

            return str.toString();
        }
    }

    public enum Status {
        CONSISTENT, INCONSISTENT, INCONCLUSIVE;

        @Override
        public String toString() {
            return switch (this) {
                case CONSISTENT -> "Consistent";
                case INCONSISTENT -> "Inconsistent";
                case INCONCLUSIVE -> "Inconclusive";
            };
        }
    }

}
